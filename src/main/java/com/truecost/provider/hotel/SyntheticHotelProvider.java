package com.truecost.provider.hotel;

import com.truecost.domain.Money;
import com.truecost.provider.ProviderResult;
import java.nio.charset.StandardCharsets;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.CRC32;
import org.springframework.stereotype.Component;

/**
 * Deterministic fallback hotel pricing, used for load tests and whenever the Amadeus sandbox is
 * down or disabled. A request derived seed picks a nightly rate within a small hardcoded band per
 * destination, so results are reproducible.
 */
@Component
public class SyntheticHotelProvider implements HotelProvider {

    private static final Map<String, long[]> NIGHTLY_RATE_BAND_CENTS_BY_DESTINATION = Map.of(
            "PHL", new long[] {11000, 18000},
            "BOS", new long[] {15000, 24000},
            "DCA", new long[] {14000, 22000},
            "MTK", new long[] {20000, 34000},
            "HUD", new long[] {13000, 21000});
    private static final long[] DEFAULT_BAND_CENTS = {12000, 20000};
    private static final String PLACEHOLDER_HOTEL_NAME = "TrueCost Sample Hotel";

    @Override
    public String name() {
        return "SYNTHETIC";
    }

    @Override
    public ProviderResult<List<HotelOffer>> fetchOffers(HotelRequest request) {
        int nights = (int) ChronoUnit.DAYS.between(request.checkIn(), request.checkOut());
        if (nights <= 0) {
            return ProviderResult.absent("checkout date must be after checkin date");
        }

        long[] band = NIGHTLY_RATE_BAND_CENTS_BY_DESTINATION.getOrDefault(request.destinationCode(), DEFAULT_BAND_CENTS);
        Random random = new Random(stableSeed(request));
        long nightlyRateCents = band[0] + Math.round(random.nextDouble() * (band[1] - band[0]));
        long totalCents = nightlyRateCents * nights;

        HotelOffer offer = new HotelOffer(PLACEHOLDER_HOTEL_NAME, Money.ofCents(totalCents), nights, name());
        return ProviderResult.present(List.of(offer));
    }

    private static long stableSeed(HotelRequest request) {
        String canonical = String.join("|", request.destinationCode(), request.checkIn().toString(),
                request.checkOut().toString());
        CRC32 crc32 = new CRC32();
        crc32.update(canonical.getBytes(StandardCharsets.UTF_8));
        return crc32.getValue();
    }
}
