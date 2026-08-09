# Aggregation API and caching design, Phase 5

This document specifies Phase 5, the `POST /api/v1/trips/plan` endpoint, the `aggregate/` fan-out
orchestrator, and the full `cache/` stack, Caffeine L1, Redis L2, keyed singleflight, and stale
while revalidate. It is written so a Sonnet agent translates it into Java without further
architectural judgment calls on anything decided here. Every request and response record is
reconciled against the real types built in Phases 2 through 4. The prose follows the project code
standard, no em dashes, no en dashes, and no semicolons. Field-listing and key-format blocks use
the same spec notation as docs/design/toll-strategy-engine.md and carry no Java statement
terminators.

All money in the response is a long count of whole cents, matching the domain `Money` value type
and the `long totalCents` the strategy engine already returns. The aggregator never serializes
`Money` directly, which would nest as `{"cents": N}`, it reads `Money.cents()` into a flat
`long ...Cents` field.

## Section 0, what already exists and what this phase adds

Phase 5 owns three new packages from the PLAN repository layout.

- `api/` holds `TripController` and the request and response records.
- `aggregate/` holds `TripPlanner`, the orchestrator, plus the response assembly and ranking.
- `cache/` holds the two tier cache, the singleflight, the Redis lock, the envelope type, and the
  TTL table.

It consumes, unchanged, `RouteClient`, `CrossingDetector`, `TollTimeline`, `CongestionPricer`,
`TollStrategyEngine`, the three provider SPIs, and `ResilientHttpClient`. It adds three small
persistence methods and one small provider refactor, all listed in Section 8, and it adds the
Caffeine dependency, which pom.xml does not yet carry.

## Section 1, the request and response shape

### 1.1 The request

The endpoint routing needs coordinates, the rental and hotel providers key their inventory by an
airport or city code, and there is no coordinate to code gazetteer anywhere in the system. Rather
than have the aggregator guess a code from a latitude and longitude, the request carries both,
coordinates for OSRM and the codes the providers already expect. This is the smallest correct
reconciliation of `RouteClient.fetchRoute(lat,lng,...)`, `RentalQuoteRequest.pickupLocationCode`,
and `HotelRequest.destinationCode`. A later `LocationResolver` that derives the code from
coordinates is deferred and noted in Section 8.

```
record TripPlanRequest(
    double originLat,
    double originLng,
    String pickupLocationCode,
    double destLat,
    double destLng,
    String destinationCode,
    Instant departureAt,
    Instant returnAt,
    boolean hasPersonalEzpass,
    String carClass
)
```

`departureAt` and `returnAt` are ISO-8601 instants, which is what `TollTimeline.price` and
`CongestionPricer.price` already accept and what `SearchRequested.avsc` uses. `carClass` is
nullable, matching the optional car class in PLAN and in the Avro schema. Validation returns HTTP
400 when `returnAt` is not strictly after `departureAt`, when a coordinate is out of the valid
range, or when a location code is blank. `rentalDays` is derived, not sent, as
`ChronoUnit.DAYS.between` of the two instants resolved to `America/New_York` local dates, so a
Friday 6pm to Sunday 10am trip is two rental days, matching how `SyntheticRentalProvider` already
computes days from `pickupDate` and `returnDate`.

When `carClass` is null the aggregator fans out across every code in `vehicle_class`, one rental
fetch per class, and the response carries one option per company per class. When `carClass` is set
it fans out for that one class only.

### 1.2 A weekend rental trip has two legs, not one

A weekend rental drives origin to destination on departure and destination back to origin on
return. PLAN's route toll cost component must reflect both drives, not only the outbound one,
since a real renter pays tolls and burns fuel in each direction. This is not a simplification
choice, it falls directly out of how Phases 2 and 3 already work: `TollTimeline.price` and
`CongestionPricer.price` each take exactly one `Route`, and the Phase 2 golden tests
(`GoldenRouteTest.philadelphiaToManhattanReturnPricesLincolnTunnelDifferentlyAcrossTheWeekendPeakBoundary`
and `GoldenRouteTest.hudsonValleyToManhattanReturnCrossesAllThreeIncludingCuomoBridgeEastbound`)
already treat the return drive as its own independent `Route` and its own independent
`TollTimeline.price` call, priced at its own instant. Nothing in Phase 2 or 3 merges the two legs,
that job belongs to whichever layer assembles the whole trip, which is this phase. Every place
below that mentions the outbound leg has an exact mirror for the return leg, keyed by
`destLat/destLng` to `originLat/originLng` and priced at `returnAt` instead of `departureAt`.

## Section 2, the route hash and the cache keys

### 2.1 The route hash, revising geohash-5 to geohash-6

PLAN recommends a geohash-5 of the endpoints. A geohash-5 cell is roughly 4.9 kilometers on a
side, which is too coarse for an endpoint, because the OSRM route computed for one corner of a 4.9
kilometer cell can enter the toll network through a different crossing than a route from the
opposite corner, and crossing detection runs at a 50 meter threshold. This phase revises the cache
route hash to geohash-6, a cell roughly 1.2 kilometers by 0.6 kilometers, which coalesces near
identical searches while keeping the endpoint on the correct approach to the toll network. The
coarser geohash-5 is retained only for the Phase 6 hot route demand counting key, where
aggregating more searches per bucket is the point, so the two precisions serve two different
purposes and are deliberately not the same.

```
outboundRouteHash = gh6(originLat, originLng) + "-" + gh6(destLat, destLng)
returnRouteHash   = gh6(destLat, destLng) + "-" + gh6(originLat, originLng)
```

`gh6` is the standard base32 geohash truncated to six characters. Because `gh6(origin)` and
`gh6(dest)` differ for every real NYC weekend trip, `outboundRouteHash` and `returnRouteHash` are
always distinct strings even though no separate leg tag is added to the key, and a later search of
the reverse trip at a matching bucket reuses one leg's entry for free, a bonus, not a requirement.

To make every cached value a deterministic function of its key, and so a Phase 6 prefetch that
reconstructs a fetch from `SearchRequested` lands on the same entry as the synchronous path, the
route loader queries OSRM at the geohash cell center of each endpoint, not at the caller's exact
coordinates. The `QuoteSnapshot` Avro record already carries the exact endpoint coordinates
precisely because the route hash is lossy, so accuracy analysis is unaffected.

### 2.2 The departure and return buckets, why a date bucket alone is wrong for tolls

Toll and congestion pricing is time of day sensitive. Reference data prices the Port Authority
Hudson crossings on windows like 06:00 to 10:00 and prices congestion on peak windows of 05:00 to
21:00 weekday and 09:00 to 21:00 weekend. A date only bucket would price a 3am departure identically
to a 3pm departure, which is wrong. Every window boundary in the seed falls on a whole local hour,
so this phase buckets both the departure and the return instant to their containing local hour in
`America/New_York`.

```
departureBucket = departureAt resolved to America/New_York, formatted yyyyMMddHH
returnBucket    = returnAt resolved to America/New_York, formatted yyyyMMddHH
```

The tolls loader for each leg prices at the canonical start of its bucket hour, the instant
`yyyyMMddHH:00:00` in `America/New_York`, not at the caller's exact instant, so the cached tolls
are a deterministic function of the key regardless of which request populates the entry first. The
residual approximation is that two departures in the same hour whose crossing arrival straddles a
boundary share one price, which is the intended caching tradeoff and is exactly the sense in which
PLAN calls toll cost deterministic per departure time bucket. If a future golden pricing test
proves the hour is too coarse near a boundary, narrowing to a 30 minute bucket is a one line change
to the formatter, and it is a mechanical knob, not an architectural decision, so it is left to
Sonnet. The hour is the design default.

The separate `dateBucket = departureAt resolved to America/New_York, formatted yyyy-MM-dd` is
carried in `TripContext` and is the field the Phase 6 `SearchRequested` event uses for demand
grouping.

### 2.3 The literal key for every data class

Every key is prefixed by its data class and a schema version segment `v1`, so a schema change
invalidates cleanly by bumping the segment, and the RUNBOOK `redis-cli --scan --pattern
'rental:*'` style still matches.

```
route geometry   route:v1:{legRouteHash}
priced tolls     tolls:v1:{legRouteHash}:{tollClass}:{legBucket}
rental quotes    rental:v1:{provider}:{pickupLocationCode}:{carClassCode}:{pickupDate}:{returnDate}
fuel price       fuel:v1:price:{eiaRegion}
hotel offers     hotel:v1:{provider}:{destinationCode}:{checkIn}:{checkOut}
```

`legRouteHash` and `legBucket` are `outboundRouteHash`/`departureBucket` for the outbound leg and
`returnRouteHash`/`returnBucket` for the return leg, so there are two `route:v1` entries and two
`tolls:v1` entries per trip, never one.

Notes that remove ambiguity.

- The tolls key uses the agency `tollClass`, for example PASSENGER, not the rental car class code,
  because `TollRateRepository` resolves a rental `vehicle_class.code` to its `toll_class`
  internally and every currently seeded rental class maps to PASSENGER. See Section 5.3 for
  exactly how the loader still calls the Phase 2 API, which requires a concrete
  `vehicle_class.code` argument, while the cache key stays at the coarser `tollClass` grain so one
  entry serves every car class.
- The rental key is per provider and per car class, not per company, because the SPI
  `fetchQuotes` returns a list spanning every company for one car class in one upstream call, so
  one fetch and one cache entry already covers every company, and the response breaks them out.
  `provider` is `RentalQuoteProvider.name()`, SYNTHETIC or RAPIDAPI. `pickupDate` and `returnDate`
  are the local dates, since rental pricing has no time of day component, so the departure hour is
  deliberately absent from this key.
- The fuel key is the region gas price, not a per trip fuel estimate. `eiaRegion` is the EIA
  duoarea, `Y35NY` today. What is fetched upstream and cached for 24 hours is the regional price
  per gallon. The per trip estimate, distance divided by mpg times price, is a cheap in process
  computation done per car class after the price resolves, so the cached data class is exactly the
  fuel price PLAN lists.
- The hotel key uses `checkIn` equal to the departure local date and `checkOut` equal to the
  return local date.

### 2.4 What Redis holds, what Caffeine holds, the envelope

A bare value with a plain Redis TTL cannot support stale while revalidate, because Redis would
evict the entry at the logical TTL and the stale window would have nothing to serve. So Redis
holds an envelope and the physical Redis TTL is set to twice the logical TTL.

```
envelope JSON  {"value": <value JSON>, "fetchedAt": <epochMilli>, "expiresAt": <epochMilli>}
redis PEXPIRE  2 x logicalTtl
```

`expiresAt` is `fetchedAt + logicalTtl`. An entry is fresh while `now < expiresAt`, stale while
`expiresAt <= now < expiresAt + logicalTtl`, and beyond that Redis has already evicted it, so a
hard expired entry is simply a Redis miss. The stale window and the physical eviction line up
exactly, which is why the background refresh landing within 2x TTL is provable.

The value JSON per class is the domain object the loader produced.

```
route:v1   { distanceMeters, durationSeconds, points: [ {lat, lng, cumulativeDurationSeconds} ... ] }
tolls:v1   { pricedCrossings: [PricedCrossing ...], congestionDays: [CongestionDay ...] }
rental:v1  [ RentalQuote ... ]
fuel:v1    { pricePerGallon: <double>, isFallback: <boolean> }
hotel:v1   [ HotelOffer ... ]
```

The route value stores the decoded `Route`. If the polyline blob size becomes a concern, storing
the encoded polyline plus the segment duration array and re-zipping on read through the existing
`RouteClient` logic is an equivalent compact form, and that is a mechanical choice left to Sonnet.
The tolls value stores exactly the two lists `TollStrategyEngine` consumes for that one leg, so
combining both legs at read time needs no further I/O, see Section 5.3.

Caffeine L1 is one `Cache<String, CacheEnvelope>` with `expireAfterWrite` of 30 seconds and a
`maximumSize` on the order of 10000. It is keyed by the identical Redis key string and holds the
deserialized `CacheEnvelope` object, so an L1 hit skips both the Redis round trip and JSON
deserialization, and it evaluates freshness from the same `expiresAt`, so an L1 hit can itself be
fresh or stale. The 30 second L1 TTL is what absorbs an identical burst inside one instance, and it
is deliberately shorter than every logical TTL so L1 never masks a stale transition for longer than
30 seconds.

## Section 3, keyed singleflight

### 3.1 In process, a ConcurrentHashMap of in-flight futures

The in process singleflight is a `ConcurrentHashMap<String, CompletableFuture<CacheEnvelope>>` of
in-flight loads, not a library. A hand rolled map is about fifteen lines, and it is preferred over
Caffeine's `AsyncLoadingCache`, which also gives per key coalescing, because the loader here must
additionally acquire the cross instance Redis lock and re-check Redis before calling upstream, and
an explicit future map keeps that coordination visible rather than hidden behind an async cache's
loader. The first thread to arrive for a key installs its future with `putIfAbsent` and becomes the
local leader. Every other local thread for that key gets the existing future back and blocks on it,
so N concurrent identical misses inside one instance collapse to one loader invocation. The leader
always removes the key from the map in a finally block, keyed on the exact future instance with
`remove(key, future)`, so a later miss starts a fresh load.

### 3.2 Across instances, a short Redis lock

The single local leader is the only thread that contends for the cross instance lock, so across M
instances at most M threads contend and exactly one wins, giving exactly one upstream fetch
globally. The lock is a `SET` with `NX` and `PX`.

```
acquire   SET lock:{key} {instanceId}:{uuid} NX PX {lockTtlMs}
release   Lua compare and delete, delete lock:{key} only if its value equals {instanceId}:{uuid}
```

`lockTtlMs` is 5000, comfortably above the provider budget of a 2 second timeout plus one retry, so
the lock outlives the slowest legitimate fetch. The value is a per acquisition token, `instanceId`
plus a UUID, and release is a Lua compare and delete so a leader never deletes a lock that already
expired and was retaken by a successor.

The leader, after winning the lock, re-reads the value key once, because another instance may have
populated it between the local miss and the lock acquisition, and if it is now fresh the leader
uses it and skips the upstream call. Otherwise the leader calls the loader exactly once, writes the
envelope with the 2x physical TTL, then releases the lock.

A follower that loses the `SET NX` polls the value key on a short interval, 25 to 50 milliseconds,
up to `lockTtlMs`, and uses the value the moment the leader writes it. Polling, not pub sub, is the
choice here, because at this scale a sub 50 millisecond poll is simpler and adds no subscription
lifecycle, and pub sub wakeup is noted as a later optimization only.

If the lock holder crashes mid fetch, the lock auto expires at `lockTtlMs`, no value is ever
written by the crashed holder so nothing is corrupted, and the follower's poll times out and it
loops back to attempt `SET NX` itself, at which point the expired lock lets it acquire and become
the new leader. This bounds the worst case for a waiter at the lock TTL plus one fetch.

### 3.3 Why this satisfies the 50 concurrent test

The WireMock counted acceptance test drives 50 concurrent identical requests. Within one instance
the `putIfAbsent` map yields one local leader per key, and that one leader's single `SET NX` win
yields one loader call, which is one WireMock hit per provider key. Across instances the same holds
because only the per instance leaders contend on the Redis lock. Outbound route, outbound tolls,
return route, return tolls, rental, fuel, and hotel each have their own key, so the test asserts
exactly one hit against each of the OSRM, RapidAPI, EIA, and Amadeus stubs.

## Section 4, stale while revalidate

### 4.1 The read algorithm

```
get(key, logicalTtl, loader):
  env = caffeine.getIfPresent(key)
  if env is null:
    env = redisReadEnvelope(key)
    if env not null: caffeine.put(key, env)
  now = clock.now()
  if env not null and now < env.expiresAt:
    return Hit(env.value, FRESH)
  if env not null and now < env.expiresAt + logicalTtl:
    triggerBackgroundRefresh(key, logicalTtl, loader)
    return Hit(env.value, STALE)
  return Hit(singleflightLoad(key, logicalTtl, loader).value, FRESH)
```

Staleness is detected purely by comparing the wall clock to the envelope `expiresAt`, no separate
stale marker is stored. A stale read returns immediately with the stale value and never blocks on
the refresh, which is what keeps the warm and stale paths fast.

### 4.2 Who refreshes and how redundant refreshes are avoided

The background refresh runs on the shared application scoped virtual thread executor,
`Executors.newVirtualThreadPerTaskExecutor()` exposed as a bean, which is the virtual thread choice
PLAN states for fan-out and which outlives the triggering request so the refresh can complete after
the stale response is already sent.

Redundant refreshes are prevented at two levels, and both reuse the singleflight from Section 3
rather than adding a second mechanism.

- In process, `triggerBackgroundRefresh` submits a task that calls the same `singleflightLoad`. If
  a blocking cold load or an earlier refresh is already in flight for the key, the `putIfAbsent`
  returns the existing future and the task attaches and discards, so many stale reads across many
  threads produce one refresh. A cold miss and a stale read for the same key also share that one in
  flight load, so they never both fetch.
- Across instances, the refresh still goes through the `SET NX` lock, so only the instance that
  wins the lock calls upstream. An instance that loses the lock on a refresh simply returns,
  because it already served the stale value and does not need the result, so a stale read on one
  instance while another is refreshing adds no duplicate upstream call.

### 4.3 The freshness flag and the within 2x TTL guarantee

The `DataFreshness` returned by `get` flows straight into the response, STALE on the class that was
served from the stale window and FRESH otherwise. Because the physical Redis TTL is 2x the logical
TTL, a stale entry is guaranteed present in Redis for the entire second logical TTL span, the
background refresh triggered on the first stale read writes a new envelope with a fresh
`expiresAt` well inside that span, and a subsequent read sees FRESH again. That is the mechanism
the stale response test asserts, a STALE flag on the served response and a fresh entry landing
within 2x TTL.

## Section 5, fan-out orchestration

### 5.1 Structured concurrency choice, a plain virtual thread executor

`TripPlanner` fans out on the shared virtual thread executor using `CompletableFuture.supplyAsync`
per task, not Java 21 `StructuredTaskScope`. `StructuredTaskScope` is a preview API in Java 21 and
would force `--enable-preview` across compile, surefire, and the runtime image, which this
production targeted build should not take on for a fan-out that a plain executor expresses cleanly.
The whole application already runs with `spring.threads.virtual.enabled` true and PLAN commits to
virtual threads with no reactive stack, so a `CompletableFuture` over a virtual thread executor is
the idiomatic fit. The join carries an overall deadline on the order of the 3 second cold path
budget, after which any unfinished task is treated as absent and its field is flagged, so one slow
dependency never blocks the response.

### 5.2 The task graph, both legs

```
outboundRoute = cache.get(route:v1:{outboundRouteHash}, 7d, loader = RouteClient.fetchRoute at origin and dest cell centers)
outboundTolls = cache.get(tolls:v1:{outboundRouteHash}:{tollClass}:{departureBucket}, 24h, loader = leg pricer on outboundRoute at the departure bucket instant)

returnRoute   = cache.get(route:v1:{returnRouteHash}, 7d, loader = RouteClient.fetchRoute at dest and origin cell centers)
returnTolls   = cache.get(tolls:v1:{returnRouteHash}:{tollClass}:{returnBucket}, 24h, loader = leg pricer on returnRoute at the return bucket instant)

fuelPrice     = cache.get(fuel:v1:price:{region}, 24h, loader = EIA fetch)
hotelOffers   = cache.get(hotel:v1:..., 6h, loader = HotelProvider.fetchOffers)
rental[class] = cache.get(rental:v1:...:{class}:..., 15m, loader = RentalQuoteProvider.fetchQuotes) for each provider and each car class
```

`outboundTolls` depends on `outboundRoute` and `returnTolls` depends on `returnRoute`, so those two
chains run after their own route future, and the two legs are independent of each other and start
concurrently. `hotel` and every `rental` task are independent of both legs and start immediately.

### 5.3 The leg pricer, bridging to the Phase 2 and Phase 3 APIs

`TollTimeline.price(Route route, Instant departureInstant, String vehicleClassCode)` and
`TollRateRepository` take a concrete rental `vehicle_class.code` argument and resolve its
`toll_class` internally, they do not accept `toll_class` directly. Since every currently seeded
rental car class maps to `toll_class` PASSENGER, the leg pricer calls `TollTimeline.price` once
per leg with a single representative `vehicle_class.code` for the `tollClass` being priced, for
example the lexicographically first seeded code mapping to that class, `ECONOMY`, and the
resulting `PricedCrossing` amounts are identical for every other PASSENGER class. This is exactly
why the `tolls:v1` cache key is keyed by `tollClass` rather than by car class, one fetch and one
cache entry correctly serves every car class in the response. If a future reseed ever introduces a
rental class mapping to a different `toll_class`, the loader needs one representative per distinct
`toll_class`, a mechanical extension, not something to build now.

`CongestionPricer.price(Route route, List<DetectedCrossing> detectedCrossings, Instant
departureInstant, String tollClass, String paymentType)` prices one payment type per call and
`CrossingDetector.detect(route)` supplies the `detectedCrossings` argument it needs, which the leg
pricer already ran to build `TollTimeline`'s input. Since `CongestionDay` carries both
`netEzpassCents` and `netMailCents`, the leg pricer calls `CongestionPricer.price` twice per leg, at
the same instant and tollClass, once with paymentType EZPASS and once with TOLLS_BY_MAIL, and folds
the two `Optional<CongestionCharge>` results into at most one `CongestionDay` for that leg's charge
date, `peak` and `creditTunnel` taken from either call since both share the same schedule row apart
from payment type.

So one `tolls:v1` cache entry's value is `{ pricedCrossings: List<PricedCrossing>, congestionDays:
List<CongestionDay> }` for that single leg, produced by one `CrossingDetector.detect`, one
`TollTimeline.price`, and two `CongestionPricer.price` calls.

### 5.4 Combining both legs before the strategy engine

After both legs resolve, the aggregator builds one `StrategyInput` for the whole trip.

```
crossings = outboundTolls.pricedCrossings + returnTolls.pricedCrossings
```

`congestionDays` merges `outboundTolls.congestionDays` and `returnTolls.congestionDays` by
`date`. The MTA charge is levied once per calendar date regardless of how many times the zone is
entered that date, and a weekend trip's outbound and return legs almost always fall on different
calendar dates, so the common case is a plain concatenation. On the rare same day round trip where
both legs produce a `CongestionDay` for the same date, keep only one of the two, the one with the
later `chargeInstant`, rather than double charging a once per day fee. Which of the two amounts
that keeps only matters when a peak boundary falls between the two legs' instants on that shared
date, a narrow edge case documented here as a tie break rather than a blocking decision.

Total distance for the fuel estimate is `outboundRoute.distanceMeters() + returnRoute.distanceMeters()`,
not one leg alone, since fuel is burned driving both ways. `TripContext` carries both legs'
identifying data, replacing a single route hash and bucket pair.

```
record TripContext(
    int rentalDays,
    double totalDistanceMiles,
    String tollClass,
    String outboundRouteHash,
    String returnRouteHash,
    String departureBucket,
    String returnBucket,
    String dateBucket
)
```

The combined `crossings` and `congestionDays` feed one `StrategyInput` per rental company, together
with that company's `TollProgram` list from Section 8, and `TollStrategyEngine.evaluate` runs once
per rental quote exactly as Section 5.5 describes.

### 5.5 From futures to options, and the partial flag

After the join, for each rental quote the aggregator runs `TollStrategyEngine.evaluate` in process
with a `StrategyInput` of the shared combined crossings and congestion days from Section 5.4, the
derived `rentalDays`, the request `hasPersonalEzpass`, and that quote's company toll programs
loaded from `toll_program`. The engine is pure and cheap, so it runs per option with no I/O and no
caching of its own. The option's fuel is computed from the cached price, the total two leg
distance, and the class mpg, its hotel is the cheapest cached offer, and its true total is
assembled per Section 1.

A provider Absent result becomes a flag on the option, never a thrown exception, because the SPI
already guarantees `ProviderResult.Absent` rather than an exception crossing the aggregation
boundary.

- A rental provider Absent contributes no quotes, so it simply yields no options. If every rental
  provider is Absent, the response is 200 with an empty `options` list, a null `recommendation`,
  and `SharedFreshness` reflecting the failure, rather than a 500.
- Fuel is never Absent, because `FuelCostService` falls back to the static price, so its signal is
  `fuelPriceIsFallback` true and `SharedFreshness.fuel` UNAVAILABLE.
- Hotel Absent sets `hotelCents` 0, `SharedFreshness.hotel` UNAVAILABLE, and `partial` true on every
  option.
- Either leg's route or tolls unavailable, for example OSRM timing out, sets
  `SharedFreshness.tolls` UNAVAILABLE, omits the toll line items and the strategy from affected
  options, ranks them on rental plus fuel plus hotel, and sets `partial` true. This keeps the
  thesis centerpiece degrading gracefully rather than failing the whole request, and the tolls
  cache TTLs of 7 days and 24 hours make this the rare path.

`partial` on an option is the logical or of tolls unavailable, hotel unavailable, and fuel
fallback, so a fully live option is not partial and a snapshot golden scenario computed cold with
synthetic providers is deterministic and not partial.

## Section 6, the response tree

```
record TripPlanResponse(
    List<TripOption> options,
    Recommendation recommendation,
    TripContext context,
    SharedFreshness freshness
)
```

`options` is ranked ascending by `trueTotalCents`. The ranking tie break is `trueTotalCents`
ascending, then `companyCode` ascending, then `carClassCode` ascending, so the order is a total
order and the snapshot test is stable.

```
record TripOption(
    String companyCode,
    String companyName,
    String carClassCode,
    long rentalCents,
    long tollProgramFeeCents,
    long tollTotalCents,
    long congestionCents,
    long fuelCents,
    long hotelCents,
    long trueTotalCents,
    ChosenStrategy strategy,
    OptionProvenance provenance,
    boolean partial
)
```

Reconciliation notes, these are the load bearing decisions where the response must not invent
fields the source types cannot supply.

- `rentalCents` is `RentalQuote.totalCost().cents()`. PLAN calls this the base rate, but
  `RentalQuote` carries only `totalCost`, `rentalDays`, and `source`, it does not decompose base
  versus taxes and fees. The `QuoteSnapshot` Avro record does carry `baseRateCents` and
  `taxesAndFeesCents`, but the live `RentalQuote` domain record does not, so the response exposes
  the single rental total and names it `rentalCents`. Do not add a base or fees split the provider
  cannot produce.
- `tollProgramFeeCents`, `tollTotalCents`, `congestionCents` are the `feeCents`, `tollCents`,
  `congestionCents` of the winning `StrategyCost`, found by selecting from
  `StrategyResult.candidates()` the candidate whose `strategy` and `programName` equal the result
  `winner` and `programName`. These three already sum to the strategy total, so no separate toll
  total is computed.
- `fuelCents` is `FuelEstimate.cost().cents()`, computed from the combined two leg distance per
  Section 5.4. Fuel varies per car class, since `mpg` comes from `vehicle_class.default_mpg`, so
  options of the same class share it.
- `hotelCents` is the cheapest `HotelOffer.totalCost().cents()` across the returned offers, or 0
  when hotel is unavailable, in which case `partial` is true and `SharedFreshness.hotel` is
  UNAVAILABLE.
- `trueTotalCents` is `rentalCents + strategy.totalCents + fuelCents + hotelCents`. The strategy
  total is the fee plus toll plus congestion decomposition already shown, so the true total never
  double counts.

```
record ChosenStrategy(
    String kind,
    String programName,
    long totalCents,
    String explanation,
    List<StrategyCandidate> candidates
)

record StrategyCandidate(
    String strategy,
    String programName,
    long totalCents,
    long feeCents,
    long tollCents,
    long congestionCents,
    int feeDays
)
```

`kind` is the `Strategy` enum name, PERSONAL_TAG, COMPANY_PER_CROSSING, COMPANY_UNLIMITED, or
NO_ARRANGEMENT. `explanation` is the exact string `TollStrategyEngine` already builds. `candidates`
is a field for field copy of `StrategyResult.candidates()`, one `StrategyCandidate` per
`StrategyCost`, so the API surfaces the runners up the engine already retained without recomputing
anything.

```
record OptionProvenance(
    String rentalSource,
    boolean fuelPriceIsFallback,
    String hotelSource
)
```

`rentalSource` is `RentalQuote.source()`, SYNTHETIC or RAPIDAPI, so a placeholder quote is never
shown as a live price. `fuelPriceIsFallback` is `FuelEstimate.priceIsFallback()`. `hotelSource` is
the chosen `HotelOffer.source()` or null when hotel is unavailable.

```
record Recommendation(
    String companyCode,
    String carClassCode,
    long trueTotalCents,
    long savingsOverNextCents,
    String summary
)
```

The recommendation is the rank one option. `savingsOverNextCents` is the rank two option total
minus the rank one total, or 0 when there is a single option. `summary` names the winner, its
all-in total, and the saving, and it quotes the option's strategy explanation so the toll reasoning
surfaces at the top of the response.

```
record SharedFreshness(
    DataFreshness route,
    DataFreshness tolls,
    DataFreshness fuel,
    DataFreshness hotel
)

enum DataFreshness { FRESH, STALE, UNAVAILABLE }
```

`SharedFreshness.tolls` and `.route` reflect the worse of the two legs, STALE if either leg's route
or tolls entry was served stale and neither was UNAVAILABLE, UNAVAILABLE if either leg failed
outright. `SharedFreshness` carries the four trip level data classes, so every shared field in the
response has a freshness marker. FRESH means served inside the logical TTL, STALE means served from
the stale while revalidate window with a refresh already triggered, UNAVAILABLE means the provider
returned Absent and the field is a fallback or zero. This is the per field freshness the acceptance
criteria require, and the stale flag the stale response test asserts is `DataFreshness.STALE`
appearing on the class that was served stale.

## Section 7, the TTL table, confirmed and one revision

| Data class | Logical TTL | Physical Redis TTL | Note |
| --- | --- | --- | --- |
| route geometry | 7 days | 14 days | confirmed, one entry per leg |
| priced tolls | 24 hours | 48 hours | confirmed, one entry per leg, keyed by hour bucket per Section 2.2 |
| rental quotes | 15 minutes | 30 minutes | confirmed |
| fuel price | 24 hours | 48 hours | confirmed |
| hotel offers | 6 hours | 12 hours | confirmed |

The logical TTL column is exactly PLAN's table. The physical column, twice the logical, is added by
this phase and is the mechanism behind stale while revalidate, not a change to the published TTLs.

## Section 8, required small additions to existing code

- `TollProgramRepository.findByCompanyCode(String companyCode)` returning `List<TollProgram>` in
  the `toll.strategy` shape, joining `toll_program` to `rental_company` on `company_id` and
  filtering `rental_company.code`, mapping columns to engine fields as follows. `program_type`
  USAGE_DAY maps to PER_CROSSING_USAGE_DAY, ALL_RENTAL_DAYS to PER_CROSSING_ALL_DAYS,
  UNLIMITED_DAILY to UNLIMITED_DAILY. `feeCapped` is `cap_cents is not null` and `feeCapCents` is
  the cap or 0. `tollRateBasis` maps EZPASS_RATE to EZPASS and MAX_CASH_RATE to MAX_CASH, and is an
  inert EZPASS for unlimited. `coversCongestion` is `covers_congestion`. The `fee_requires_usage`
  column is intentionally not mapped, because the engine already derives fee gating from the usage
  day count being greater than zero. This is the Phase 3 reconciliation checkpoint the reference
  data doc flagged.
- `VehicleClassRepository` already has `findTollClass` and `findDefaultMpg` from Phase 4, no change
  needed there. Add one query, or reuse `findTollClass`'s reverse, to pick one representative
  `vehicle_class.code` for a given `toll_class`, per Section 5.3, for example `select code from
  vehicle_class where toll_class = :tollClass order by code limit 1`.
- `FuelCostService` refactor, extract the EIA price fetch behind the shared two tier cache under
  `fuel:v1:price:{region}` and drop the private `AtomicReference` cache, so the one upstream fetch
  guarantee holds across instances and `estimateFuelCost` becomes a pure computation of distance,
  mpg, price, and the fallback flag.
- A `LocationResolver` that maps coordinates to a location code is explicitly deferred, the request
  carries the codes for now.
- pom.xml gains the Caffeine dependency, `com.github.ben-manes.caffeine:caffeine`, which the build
  does not yet carry. Redis is already present through `spring-boot-starter-data-redis`, and the
  singleflight lock uses `StringRedisTemplate` with a Lua release script.

## Section 9, observability handoff to Phase 7

Phase 5 emits the counters Phase 7's cache effectiveness dashboard and alerts consume, so they
exist from the moment the cache does.

- `truecost.cache.request` counter tagged `class` and `outcome`, where outcome is l1_hit, l2_hit,
  miss, or stale.
- `truecost.cache.singleflight.coalesced` counter tagged `class`, incremented each time a thread
  attaches to an in flight future instead of loading.
- `truecost.cache.stale.served` counter tagged `class`.
- `truecost.cache.refresh` counter tagged `class` and `outcome` of leader or skipped.
- The provider call latency timer already emitted by `ResilientHttpClient` is unchanged and remains
  the per provider signal.

## Section 10, worked examples

### 10.1 Warm hit, a repeated weekend search

A user searches Manhattan to Philadelphia, departing Friday 2026-07-24T18:00 in New York, returning
Sunday 2026-07-26T14:00, no personal E-ZPass, car class MIDSIZE. The outbound keys resolve to
`route:v1:dr5reg-dr4urf` and `tolls:v1:dr5reg-dr4urf:PASSENGER:2026072418`, the return keys resolve
to `route:v1:dr4urf-dr5reg` and `tolls:v1:dr4urf-dr5reg:PASSENGER:2026072614`, and the shared keys
are `rental:v1:SYNTHETIC:PHL:MIDSIZE:2026-07-24:2026-07-26`, `fuel:v1:price:Y35NY`, and
`hotel:v1:SYNTHETIC:PHL:2026-07-24:2026-07-26`. On a warm instance all seven are fresh in Caffeine,
so the request touches neither Redis nor any upstream, runs the pure strategy engine once per
option against the combined outbound plus return crossings, and returns with every `DataFreshness`
FRESH. This is the sub 300 millisecond warm path the k6 threshold targets, since the only work is a
handful of Caffeine lookups and integer strategy computations.

### 10.2 Cold then stale, the outbound tolls entry

The first search for that route on a novel hour bucket misses everywhere. Fifty identical
concurrent requests arrive. The local singleflight elects one leader for the outbound tolls key,
the leader wins `SET lock:tolls:v1:dr5reg-dr4urf:PASSENGER:2026072418`, detects crossings on the
cached outbound route, prices at 2026-07-24T18:00 New York, writes the envelope with `expiresAt`
24 hours out and a Redis TTL of 48 hours, and the other 49 threads reuse it, so OSRM and the rate
table are touched once for this leg, and independently once more for the return leg's own key.
Twenty six hours later a request finds the outbound tolls entry present in Redis but two hours past
`expiresAt`, inside the 48 hour physical life, so the read returns the value with
`DataFreshness.STALE`, submits one background refresh on the virtual thread executor, and the
refresh, winning the Redis lock, reprices and writes a new envelope with a fresh `expiresAt`. The
next request sees FRESH again, and the refresh landed well inside the second 24 hour span, which is
the within 2x TTL guarantee.

## Section 11, deliberate changes from PLAN and what is left to Sonnet

Changed from PLAN's stated recommendations, each with its reason.

- Cache route hash is geohash-6, not geohash-5, so an endpoint stays on the correct approach to the
  50 meter detection network. Geohash-5 is kept only for the Phase 6 demand key.
- The priced tolls bucket is date plus hour, not date alone, because every toll and congestion
  window boundary is hour aligned and a date only bucket would misprice peak versus overnight.
  This directly resolves PLAN's open note that a date bucket may be too coarse for tolls.
- Route geometry is fetched at the geohash cell center and tolls are priced at the bucket start
  instant, so each cached value is a deterministic function of its key, which the snapshot test
  and the Phase 6 prefetch both rely on.
- A trip prices two independent legs, outbound and return, each with its own route and tolls cache
  entry, combined only after both resolve, in front of the strategy engine. This is not optional,
  Phase 2 and 3 already model outbound and return as separate `Route` and `TollTimeline.price`
  calls in the golden tests, and omitting the return leg would silently under price every trip by
  the entire return drive's tolls and fuel.

Left to Sonnet as mechanical detail, not architecture.

- The base32 geohash-6 encoder and the decode to cell center, both standard well defined
  algorithms.
- Whether the route value stores decoded points or the compact encoded polyline plus segment
  durations.
- Narrowing the departure or return bucket from an hour to 30 minutes only if a golden pricing test
  near a boundary demands it.
- The Redis poll interval inside the lock TTL, any value in the 25 to 50 millisecond range.
- Exact Micrometer meter names and tags, though Section 9 lists the required set.
- Jackson serialization config for the envelope and value JSON, reusing the Spring Boot defaults.
- The same day round trip congestion tie break in Section 5.4, keep the later `chargeInstant`'s
  `CongestionDay` when both legs charge the same date, is specified precisely enough to implement
  directly.

## Section 12, summary of decisions for the implementer

- The request carries both endpoint coordinates and provider location codes, and derives
  `rentalDays` and all date and hour buckets in `America/New_York`.
- A trip has two legs. Every route and tolls cache entry, loader call, and freshness signal exists
  once for the outbound direction at `departureAt` and once for the return direction at
  `returnAt`. They are combined into one `crossings` list and one deduplicated `congestionDays`
  list only when building the `StrategyInput`, and fuel distance is the sum of both legs.
- The response exposes flat `long ...Cents` fields, maps the rental total to `rentalCents` because
  the provider gives no base versus fees split, and copies the strategy winner breakdown and
  candidates straight from `StrategyResult`.
- Cache route hash is geohash-6, priced tolls are keyed by route hash, toll class, and a date plus
  hour bucket, rental by provider, location, class, and dates, fuel by region, hotel by provider,
  destination, and dates.
- `TollTimeline.price` and `TollRateRepository` require a concrete `vehicle_class.code`, not a
  `toll_class`, so the tolls loader picks one representative code per `toll_class` being priced,
  which is what makes keying the cache by `tollClass` alone correct.
- Redis stores an envelope with `fetchedAt` and `expiresAt` at a physical TTL of 2x the logical
  TTL, Caffeine stores the same envelope at 30 seconds.
- Singleflight is a ConcurrentHashMap of in flight futures per instance plus a `SET NX PX` Redis
  lock with a Lua compare and delete release, giving exactly one upstream fetch per provider key
  globally.
- Stale while revalidate serves inside the second logical TTL span, flags STALE, and refreshes once
  through the same singleflight on the virtual thread executor.
- Fan-out is `CompletableFuture` on a shared virtual thread executor, not preview
  `StructuredTaskScope`, with an overall deadline and provider Absent flowing to a per option
  `partial` flag.
