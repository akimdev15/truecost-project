package com.truecost.aggregate;

import com.fasterxml.jackson.core.type.TypeReference;
import io.micrometer.core.instrument.MeterRegistry;
import com.truecost.api.dto.ChosenStrategy;
import com.truecost.api.dto.OptionProvenance;
import com.truecost.api.dto.Recommendation;
import com.truecost.api.dto.RentalRateOverride;
import com.truecost.api.dto.SharedFreshness;
import com.truecost.api.dto.StrategyCandidate;
import com.truecost.api.dto.TripContext;
import com.truecost.api.dto.TripOption;
import com.truecost.api.dto.TripPlanRequest;
import com.truecost.api.dto.TripPlanResponse;
import com.truecost.cache.CacheKeys;
import com.truecost.cache.CacheResult;
import com.truecost.cache.DataFreshness;
import com.truecost.cache.GeoPoint;
import com.truecost.cache.Geohash;
import com.truecost.cache.TollsCacheValue;
import com.truecost.cache.TwoTierCache;
import com.truecost.domain.Money;
import com.truecost.events.EventPublisher;
import com.truecost.persist.TollProgramRepository;
import com.truecost.persist.VehicleClassRepository;
import com.truecost.provider.ProviderResult;
import com.truecost.provider.fuel.FuelCostService;
import com.truecost.provider.fuel.FuelEstimate;
import com.truecost.provider.fuel.FuelPriceResolution;
import com.truecost.provider.hotel.HotelOffer;
import com.truecost.provider.hotel.HotelProvider;
import com.truecost.provider.hotel.HotelRequest;
import com.truecost.provider.rental.RentalQuote;
import com.truecost.provider.rental.RentalQuoteProvider;
import com.truecost.provider.rental.RentalQuoteRequest;
import com.truecost.route.Route;
import com.truecost.route.RouteClient;
import com.truecost.toll.PricedCrossing;
import com.truecost.toll.strategy.CongestionDay;
import com.truecost.toll.strategy.StrategyCost;
import com.truecost.toll.strategy.StrategyInput;
import com.truecost.toll.strategy.StrategyResult;
import com.truecost.toll.strategy.TollProgram;
import com.truecost.toll.strategy.TollStrategyEngine;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Turns one TripPlanRequest into a ranked TripPlanResponse by fanning out every route, tolls,
 * rental, fuel, and hotel load onto the shared virtual thread executor, then running
 * TollStrategyEngine once per rental option against the combined two-leg crossings. A provider
 * failure never throws across this boundary, it degrades the affected option to a partial result
 * and downgrades the shared freshness instead.
 */
@Component
public class TripPlanner {

    private static final Logger log = LoggerFactory.getLogger(TripPlanner.class);

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final DateTimeFormatter HOUR_BUCKET = DateTimeFormatter.ofPattern("yyyyMMddHH");
    private static final double METERS_PER_MILE = 1609.344;

    private static final Duration ROUTE_TTL = Duration.ofDays(7);
    private static final Duration TOLLS_TTL = Duration.ofHours(24);
    private static final Duration RENTAL_TTL = Duration.ofMinutes(15);
    private static final Duration HOTEL_TTL = Duration.ofHours(6);
    private static final Duration OVERALL_DEADLINE = Duration.ofSeconds(3);

    private static final TypeReference<Route> ROUTE_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<TollsCacheValue> TOLLS_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<RentalQuote>> RENTAL_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<HotelOffer>> HOTEL_TYPE = new TypeReference<>() {
    };

    private final TwoTierCache cache;
    private final RouteClient routeClient;
    private final LegPricer legPricer;
    private final FuelCostService fuelCostService;
    private final VehicleClassRepository vehicleClassRepository;
    private final TollProgramRepository tollProgramRepository;
    private final List<RentalQuoteProvider> rentalProviders;
    private final List<HotelProvider> hotelProviders;
    private final ExecutorService virtualThreadExecutor;
    private final EventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;

    public TripPlanner(TwoTierCache cache, RouteClient routeClient, LegPricer legPricer,
            FuelCostService fuelCostService, VehicleClassRepository vehicleClassRepository,
            TollProgramRepository tollProgramRepository, List<RentalQuoteProvider> rentalProviders,
            List<HotelProvider> hotelProviders, ExecutorService virtualThreadExecutor,
            EventPublisher eventPublisher, MeterRegistry meterRegistry) {
        this.cache = cache;
        this.routeClient = routeClient;
        this.legPricer = legPricer;
        this.fuelCostService = fuelCostService;
        this.vehicleClassRepository = vehicleClassRepository;
        this.tollProgramRepository = tollProgramRepository;
        this.rentalProviders = rentalProviders;
        this.hotelProviders = hotelProviders;
        this.virtualThreadExecutor = virtualThreadExecutor;
        this.eventPublisher = eventPublisher;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Plans a trip for a real user search, publishing a SearchRequested event so the prefetcher can
     * learn the route is in demand. The prefetch path warms caches through {@link #plan(TripPlanRequest,
     * boolean)} with userInitiated false instead, so a warm never counts as a fresh user search and
     * cannot feed back into hot route detection.
     */
    public TripPlanResponse plan(TripPlanRequest request) {
        return plan(request, true);
    }

    public TripPlanResponse plan(TripPlanRequest request, boolean userInitiated) {
        Instant deadline = Instant.now().plus(OVERALL_DEADLINE);

        LocalDate departDate = request.departureAt().atZone(NEW_YORK).toLocalDate();
        LocalDate returnDate = request.returnAt().atZone(NEW_YORK).toLocalDate();
        int rentalDays = (int) Math.max(1, ChronoUnit.DAYS.between(departDate, returnDate));

        List<String> carClasses = resolveCarClasses(request.carClass());
        Map<String, String> tollClassByCar = new LinkedHashMap<>();
        for (String carClass : carClasses) {
            tollClassByCar.put(carClass, vehicleClassRepository.findTollClass(carClass));
        }
        List<String> distinctTollClasses = tollClassByCar.values().stream().distinct().sorted().toList();

        String gh6Origin = Geohash.encode(request.originLat(), request.originLng());
        String gh6Dest = Geohash.encode(request.destLat(), request.destLng());
        String outboundRouteHash = gh6Origin + "-" + gh6Dest;
        String returnRouteHash = gh6Dest + "-" + gh6Origin;
        GeoPoint originCenter = Geohash.decodeCellCenter(gh6Origin);
        GeoPoint destCenter = Geohash.decodeCellCenter(gh6Dest);

        String dateBucket = departDate.toString();
        String demandRouteKey = geohash5(gh6Origin) + "-" + geohash5(gh6Dest);
        if (userInitiated) {
            eventPublisher.publishSearch(demandRouteKey, dateBucket, request);
        }

        ZonedDateTime departZoned = request.departureAt().atZone(NEW_YORK);
        ZonedDateTime returnZoned = request.returnAt().atZone(NEW_YORK);
        String departureBucket = departZoned.format(HOUR_BUCKET);
        String returnBucket = returnZoned.format(HOUR_BUCKET);
        Instant departureBucketInstant = departZoned.truncatedTo(ChronoUnit.HOURS).toInstant();
        Instant returnBucketInstant = returnZoned.truncatedTo(ChronoUnit.HOURS).toInstant();

        CompletableFuture<Loaded<Route>> outboundRouteF = supply(() -> load(
                CacheKeys.route(outboundRouteHash), ROUTE_TTL, ROUTE_TYPE,
                () -> routeClient.fetchRoute(originCenter.lat(), originCenter.lng(), destCenter.lat(), destCenter.lng())));
        CompletableFuture<Loaded<Route>> returnRouteF = supply(() -> load(
                CacheKeys.route(returnRouteHash), ROUTE_TTL, ROUTE_TYPE,
                () -> routeClient.fetchRoute(destCenter.lat(), destCenter.lng(), originCenter.lat(), originCenter.lng())));

        Map<String, CompletableFuture<Loaded<TollsCacheValue>>> outboundTollsF = new LinkedHashMap<>();
        Map<String, CompletableFuture<Loaded<TollsCacheValue>>> returnTollsF = new LinkedHashMap<>();
        for (String tollClass : distinctTollClasses) {
            String representativeCode = vehicleClassRepository.findRepresentativeCodeForTollClass(tollClass);
            outboundTollsF.put(tollClass, outboundRouteF.thenApplyAsync(route -> priceLeg(route,
                    CacheKeys.tolls(outboundRouteHash, tollClass, departureBucket),
                    departureBucketInstant, tollClass, representativeCode), virtualThreadExecutor));
            returnTollsF.put(tollClass, returnRouteF.thenApplyAsync(route -> priceLeg(route,
                    CacheKeys.tolls(returnRouteHash, tollClass, returnBucket),
                    returnBucketInstant, tollClass, representativeCode), virtualThreadExecutor));
        }

        CompletableFuture<FuelPriceResolution> fuelF = supply(fuelCostService::resolveFuelPrice);

        List<CompletableFuture<Loaded<List<HotelOffer>>>> hotelFs = new ArrayList<>();
        for (HotelProvider provider : hotelProviders) {
            HotelRequest hotelRequest = new HotelRequest(request.destinationCode(), departDate, returnDate);
            hotelFs.add(supply(() -> load(
                    CacheKeys.hotel(provider.name(), request.destinationCode(), departDate, returnDate),
                    HOTEL_TTL, HOTEL_TYPE, () -> unwrap(provider.fetchOffers(hotelRequest)))));
        }

        List<RentalRateOverride> rateOverrides = request.rentalRateOverrides();
        boolean useUserSuppliedRates = rateOverrides != null && !rateOverrides.isEmpty();

        Map<String, List<CompletableFuture<Loaded<List<RentalQuote>>>>> rentalFs = new LinkedHashMap<>();
        for (String carClass : carClasses) {
            List<CompletableFuture<Loaded<List<RentalQuote>>>> perClass = new ArrayList<>();
            if (useUserSuppliedRates) {
                List<RentalQuote> userQuotes = rateOverrides.stream()
                        .map(o -> new RentalQuote(o.companyCode(), o.companyName(), carClass,
                                Money.ofCents(o.totalCents()), rentalDays, "USER_SUPPLIED"))
                        .toList();
                perClass.add(CompletableFuture.completedFuture(new Loaded<>(userQuotes, DataFreshness.FRESH)));
            } else {
                for (RentalQuoteProvider provider : rentalProviders) {
                    RentalQuoteRequest quoteRequest =
                            new RentalQuoteRequest(request.pickupLocationCode(), departDate, returnDate, carClass);
                    perClass.add(supply(() -> load(
                            CacheKeys.rental(provider.name(), request.pickupLocationCode(), carClass, departDate, returnDate),
                            RENTAL_TTL, RENTAL_TYPE, () -> unwrap(provider.fetchQuotes(quoteRequest)))));
                }
            }
            rentalFs.put(carClass, perClass);
        }

        Loaded<Route> outboundRoute = join(outboundRouteF, deadline);
        Loaded<Route> returnRoute = join(returnRouteF, deadline);
        Map<String, CombinedTolls> tollsByClass = new LinkedHashMap<>();
        for (String tollClass : distinctTollClasses) {
            Loaded<TollsCacheValue> out = join(outboundTollsF.get(tollClass), deadline);
            Loaded<TollsCacheValue> ret = join(returnTollsF.get(tollClass), deadline);
            tollsByClass.put(tollClass, combineLegs(out, ret));
        }
        FuelPriceResolution fuelResolution = joinFuel(fuelF, deadline);
        HotelResult hotel = combineHotels(hotelFs, deadline);

        double totalDistanceMeters =
                (outboundRoute.present() ? outboundRoute.value().distanceMeters() : 0.0)
                        + (returnRoute.present() ? returnRoute.value().distanceMeters() : 0.0);
        double totalDistanceMiles = totalDistanceMeters / METERS_PER_MILE;

        Map<String, FuelEstimate> fuelByCar = new LinkedHashMap<>();
        for (String carClass : carClasses) {
            fuelByCar.put(carClass,
                    fuelCostService.estimateFuelCost(totalDistanceMiles, carClass, fuelResolution));
        }

        Map<String, List<TollProgram>> programsByCompany = new LinkedHashMap<>();
        List<TripOption> options = new ArrayList<>();
        for (String carClass : carClasses) {
            CombinedTolls tolls = tollsByClass.get(tollClassByCar.get(carClass));
            FuelEstimate fuel = fuelByCar.get(carClass);
            for (CompletableFuture<Loaded<List<RentalQuote>>> rentalF : rentalFs.get(carClass)) {
                Loaded<List<RentalQuote>> rental = join(rentalF, deadline);
                if (!rental.present()) {
                    continue;
                }
                boolean rentalStale = rental.freshness() == DataFreshness.STALE;
                for (RentalQuote quote : rental.value()) {
                    eventPublisher.publishQuoteSnapshot(demandRouteKey, request, quote, rentalDays, rentalStale);
                    options.add(buildOption(quote, tolls, fuel, hotel, rentalDays,
                            request.hasPersonalEzpass(), programsByCompany));
                }
            }
        }

        options.sort(OPTION_ORDER);

        String tripTollClass = distinctTollClasses.isEmpty() ? "" : String.join(",", distinctTollClasses);
        TripContext context = new TripContext(rentalDays, totalDistanceMiles, tripTollClass,
                outboundRouteHash, returnRouteHash, departureBucket, returnBucket, dateBucket);

        SharedFreshness freshness = new SharedFreshness(
                worst(outboundRoute.freshness(), returnRoute.freshness()),
                rollupTolls(tollsByClass.values()),
                fuelResolution.freshness(),
                hotel.freshness());

        Recommendation recommendation = options.isEmpty() ? null : buildRecommendation(options);
        return new TripPlanResponse(options, recommendation, context, freshness);
    }

    private List<String> resolveCarClasses(String requestedCarClass) {
        if (requestedCarClass != null && !requestedCarClass.isBlank()) {
            return List.of(requestedCarClass);
        }
        return vehicleClassRepository.findAllCodes();
    }

    private Loaded<TollsCacheValue> priceLeg(Loaded<Route> route, String tollsKey, Instant bucketInstant,
            String tollClass, String representativeCode) {
        if (!route.present()) {
            return Loaded.absent();
        }
        return load(tollsKey, TOLLS_TTL, TOLLS_TYPE,
                () -> legPricer.priceLeg(route.value(), bucketInstant, tollClass, representativeCode));
    }

    private CombinedTolls combineLegs(Loaded<TollsCacheValue> outbound, Loaded<TollsCacheValue> ret) {
        if (!outbound.present() || !ret.present()) {
            return CombinedTolls.unavailable();
        }
        List<PricedCrossing> crossings = new ArrayList<>(outbound.value().pricedCrossings());
        crossings.addAll(ret.value().pricedCrossings());
        List<CongestionDay> congestionDays =
                CongestionDayMerge.merge(outbound.value().congestionDays(), ret.value().congestionDays());
        return new CombinedTolls(true, crossings, congestionDays,
                worst(outbound.freshness(), ret.freshness()));
    }

    private HotelResult combineHotels(List<CompletableFuture<Loaded<List<HotelOffer>>>> hotelFs, Instant deadline) {
        HotelOffer cheapest = null;
        DataFreshness freshness = DataFreshness.UNAVAILABLE;
        boolean anyPresent = false;
        for (CompletableFuture<Loaded<List<HotelOffer>>> hotelF : hotelFs) {
            Loaded<List<HotelOffer>> loaded = join(hotelF, deadline);
            if (!loaded.present()) {
                continue;
            }
            for (HotelOffer offer : loaded.value()) {
                if (cheapest == null || offer.totalCost().cents() < cheapest.totalCost().cents()) {
                    cheapest = offer;
                }
            }
            freshness = anyPresent ? worst(freshness, loaded.freshness()) : loaded.freshness();
            anyPresent = true;
        }
        return new HotelResult(cheapest, freshness);
    }

    private TripOption buildOption(RentalQuote quote, CombinedTolls tolls, FuelEstimate fuel, HotelResult hotel,
            int rentalDays, boolean hasPersonalEzpass, Map<String, List<TollProgram>> programsByCompany) {
        long rentalCents = quote.totalCost().cents();
        long fuelCents = fuel.cost().cents();
        long hotelCents = hotel.present() ? hotel.cheapest().totalCost().cents() : 0L;

        List<TollProgram> programs =
                programsByCompany.computeIfAbsent(quote.companyCode(), tollProgramRepository::findByCompanyCode);
        boolean tollsPriced = tolls.available() && !programs.isEmpty();

        long feeCents = 0L;
        long tollCents = 0L;
        long congestionCents = 0L;
        long strategyTotal = 0L;
        ChosenStrategy strategy = null;
        if (tollsPriced) {
            StrategyResult result = TollStrategyEngine.evaluate(new StrategyInput(
                    tolls.crossings(), tolls.congestionDays(), rentalDays, hasPersonalEzpass, programs));
            StrategyCost winner = winningCost(result);
            feeCents = winner.feeCents();
            tollCents = winner.tollCents();
            congestionCents = winner.congestionCents();
            strategyTotal = result.totalCents();
            strategy = new ChosenStrategy(result.winner().name(), result.programName(),
                    result.totalCents(), result.explanation(), toCandidates(result.candidates()));
            meterRegistry.counter("truecost.strategy.chosen", "kind", result.winner().name()).increment();
        }

        long trueTotalCents = rentalCents + strategyTotal + fuelCents + hotelCents;
        boolean partial = !tollsPriced || !hotel.present() || fuel.priceIsFallback();
        OptionProvenance provenance = new OptionProvenance(quote.source(), fuel.priceIsFallback(),
                hotel.present() ? hotel.cheapest().source() : null);

        return new TripOption(quote.companyCode(), quote.companyName(), quote.carClassCode(),
                rentalCents, feeCents, tollCents, congestionCents, fuelCents, hotelCents,
                trueTotalCents, strategy, provenance, partial);
    }

    private static StrategyCost winningCost(StrategyResult result) {
        return result.candidates().stream()
                .filter(c -> c.strategy() == result.winner()
                        && Objects.equals(c.programName(), result.programName()))
                .findFirst()
                .orElseGet(() -> {
                    log.warn("winning StrategyCost not found among candidates, winner={} programName={}",
                            result.winner(), result.programName());
                    return new StrategyCost(result.winner(), result.programName(), result.totalCents(),
                            0L, 0L, 0L, 0);
                });
    }

    private static List<StrategyCandidate> toCandidates(List<StrategyCost> costs) {
        return costs.stream()
                .map(c -> new StrategyCandidate(c.strategy().name(), c.programName(), c.totalCents(),
                        c.feeCents(), c.tollCents(), c.congestionCents(), c.feeDays()))
                .toList();
    }

    private static final Comparator<TripOption> OPTION_ORDER =
            Comparator.comparingLong(TripOption::trueTotalCents)
                    .thenComparing(TripOption::companyCode)
                    .thenComparing(TripOption::carClassCode);

    private Recommendation buildRecommendation(List<TripOption> ranked) {
        TripOption best = ranked.get(0);
        long savings = ranked.size() > 1 ? ranked.get(1).trueTotalCents() - best.trueTotalCents() : 0L;
        String summary = summarize(best, savings, ranked.size() > 1);
        return new Recommendation(best.companyCode(), best.carClassCode(), best.trueTotalCents(), savings, summary);
    }

    private static String summarize(TripOption best, long savings, boolean hasRunnerUp) {
        StringBuilder summary = new StringBuilder();
        summary.append(best.companyName()).append(" ").append(best.carClassCode())
                .append(" is the cheapest option at ").append(money(best.trueTotalCents())).append(" all in");
        if (hasRunnerUp) {
            summary.append(", saving ").append(money(savings)).append(" over the next best option");
        }
        summary.append(".");
        if (best.strategy() != null) {
            summary.append(" ").append(best.strategy().explanation());
        }
        return summary.toString();
    }

    private static DataFreshness rollupTolls(Iterable<CombinedTolls> tolls) {
        DataFreshness result = null;
        for (CombinedTolls combined : tolls) {
            DataFreshness legFreshness = combined.available() ? combined.freshness() : DataFreshness.UNAVAILABLE;
            result = result == null ? legFreshness : worst(result, legFreshness);
        }
        return result == null ? DataFreshness.UNAVAILABLE : result;
    }

    private static DataFreshness worst(DataFreshness a, DataFreshness b) {
        return severity(a) >= severity(b) ? a : b;
    }

    private static int severity(DataFreshness freshness) {
        return switch (freshness) {
            case FRESH -> 0;
            case STALE -> 1;
            case UNAVAILABLE -> 2;
        };
    }

    private static String money(long cents) {
        return "$%d.%02d".formatted(cents / 100, Math.abs(cents % 100));
    }

    /**
     * Truncates the geohash 6 cache key to its geohash 5 prefix, valid since geohash is a prefix
     * code, to get the coarser roughly five kilometre bucket used for hot route demand counting.
     */
    private static String geohash5(String geohash6) {
        return geohash6.substring(0, 5);
    }

    private <T> CompletableFuture<T> supply(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, virtualThreadExecutor);
    }

    /**
     * Logs at debug rather than warn, since singleflight coalescing means every caller for one
     * down dependency would otherwise re-log the same failure and the source provider already
     * warns once.
     */
    private <T> Loaded<T> load(String key, Duration ttl, TypeReference<T> typeRef, Supplier<T> loader) {
        try {
            CacheResult<T> result = cache.get(key, ttl, typeRef, loader);
            return new Loaded<>(result.value(), result.freshness());
        } catch (RuntimeException e) {
            log.debug("cache field unavailable, serving partial, key={} reason={}", key, e.toString());
            return Loaded.absent();
        }
    }

    private static <T> T unwrap(ProviderResult<T> result) {
        return switch (result) {
            case ProviderResult.Present<T> present -> present.value();
            case ProviderResult.Absent<T> absent ->
                    throw new IllegalStateException("provider absent, reason=" + absent.reason());
        };
    }

    private <T> Loaded<T> join(CompletableFuture<Loaded<T>> future, Instant deadline) {
        try {
            long remainingMs = Math.max(0, Duration.between(Instant.now(), deadline).toMillis());
            return future.get(remainingMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("fan-out task exceeded the overall deadline, treating the field as unavailable");
            return Loaded.absent();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            log.warn("fan-out task failed, reason={}", cause != null ? cause.toString() : e.toString());
            return Loaded.absent();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Loaded.absent();
        }
    }

    private FuelPriceResolution joinFuel(CompletableFuture<FuelPriceResolution> future, Instant deadline) {
        try {
            long remainingMs = Math.max(0, Duration.between(Instant.now(), deadline).toMillis());
            return future.get(remainingMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("fuel price resolution exceeded the overall deadline, using an unavailable fallback");
            return new FuelPriceResolution(0.0, true, DataFreshness.UNAVAILABLE);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            log.warn("fuel price resolution failed, reason={}", cause != null ? cause.toString() : e.toString());
            return new FuelPriceResolution(0.0, true, DataFreshness.UNAVAILABLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new FuelPriceResolution(0.0, true, DataFreshness.UNAVAILABLE);
        }
    }

    private record Loaded<T>(T value, DataFreshness freshness) {
        static <T> Loaded<T> absent() {
            return new Loaded<>(null, DataFreshness.UNAVAILABLE);
        }

        boolean present() {
            return value != null && freshness != DataFreshness.UNAVAILABLE;
        }
    }

    private record CombinedTolls(boolean available, List<PricedCrossing> crossings,
            List<CongestionDay> congestionDays, DataFreshness freshness) {
        static CombinedTolls unavailable() {
            return new CombinedTolls(false, List.of(), List.of(), DataFreshness.UNAVAILABLE);
        }
    }

    private record HotelResult(HotelOffer cheapest, DataFreshness freshness) {
        boolean present() {
            return cheapest != null;
        }
    }
}
