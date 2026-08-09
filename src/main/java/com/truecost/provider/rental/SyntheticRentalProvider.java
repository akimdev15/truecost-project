package com.truecost.provider.rental;

import com.truecost.domain.Money;
import com.truecost.provider.ProviderResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.CRC32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Deterministic placeholder rental pricing, the primary provider for development, tests, and load
 * testing. A seed derived from the request and company makes identical requests always return
 * identical quotes, so k6 runs and accuracy tests are reproducible.
 */
@Component
public class SyntheticRentalProvider implements RentalQuoteProvider {

    private static final Logger log = LoggerFactory.getLogger(SyntheticRentalProvider.class);
    private static final double VARIANCE_FRACTION = 0.08;

    private final Map<String, CalibrationRow> calibrationByCompanyAndClass;

    public SyntheticRentalProvider(@Value("${truecost.seed.dir:data/seeds}") String seedDir) {
        this.calibrationByCompanyAndClass = loadCalibration(Path.of(seedDir).resolve("rental_calibration.csv"));
        log.info("synthetic rental provider loaded, calibrationRows={}", calibrationByCompanyAndClass.size());
    }

    @Override
    public String name() {
        return "SYNTHETIC";
    }

    @Override
    public ProviderResult<List<RentalQuote>> fetchQuotes(RentalQuoteRequest request) {
        int rentalDays = (int) ChronoUnit.DAYS.between(request.pickupDate(), request.returnDate());
        if (rentalDays <= 0) {
            return ProviderResult.absent("return date must be after pickup date");
        }

        List<RentalQuote> quotes = calibrationByCompanyAndClass.values().stream()
                .filter(row -> row.carClassCode().equals(request.carClassCode()))
                .map(row -> priceQuote(row, request, rentalDays))
                .toList();

        if (quotes.isEmpty()) {
            return ProviderResult.absent("no rental calibration data for car class " + request.carClassCode());
        }
        return ProviderResult.present(quotes);
    }

    private RentalQuote priceQuote(CalibrationRow row, RentalQuoteRequest request, int rentalDays) {
        Random random = new Random(stableSeed(request, row.companyCode()));
        double varianceFactor = 1.0 + ((random.nextDouble() * 2 - 1) * VARIANCE_FRACTION);
        long dailyRateCents = Math.round(row.baseDailyRateCents() * (row.companyMultiplierBp() / 10_000.0) * varianceFactor);
        long totalCents = dailyRateCents * rentalDays + row.flatFeeCents();
        return new RentalQuote(row.companyCode(), row.companyName(), row.carClassCode(),
                Money.ofCents(totalCents), rentalDays, name());
    }

    /**
     * String.hashCode is not guaranteed stable across JVM versions, so the seed is built from a
     * CRC32 of a canonical pipe joined string instead, which gives the determinism this
     * provider's acceptance test requires.
     */
    private static long stableSeed(RentalQuoteRequest request, String companyCode) {
        String canonical = String.join("|", request.pickupLocationCode(), request.pickupDate().toString(),
                request.returnDate().toString(), request.carClassCode(), companyCode);
        CRC32 crc32 = new CRC32();
        crc32.update(canonical.getBytes(StandardCharsets.UTF_8));
        return crc32.getValue();
    }

    /**
     * Wraps the LinkedHashMap with Collections.unmodifiableMap rather than Map.copyOf, whose
     * iteration order is unspecified, since fetchQuotes streams values() in insertion order to
     * keep the per class quote list stable for the determinism test.
     */
    private Map<String, CalibrationRow> loadCalibration(Path file) {
        try {
            Map<String, CalibrationRow> rows = new LinkedHashMap<>();
            for (String line : Files.readAllLines(file)) {
                if (line.isBlank() || line.startsWith("#") || line.startsWith("company_code")) {
                    continue;
                }
                String[] f = line.split(",", -1);
                CalibrationRow row = new CalibrationRow(f[0], f[1], f[2],
                        Long.parseLong(f[3]), Long.parseLong(f[4]), Long.parseLong(f[5]));
                rows.put(row.companyCode() + ":" + row.carClassCode(), row);
            }
            return Collections.unmodifiableMap(rows);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read rental calibration seed " + file, e);
        }
    }

    private record CalibrationRow(
            String companyCode,
            String companyName,
            String carClassCode,
            long baseDailyRateCents,
            long companyMultiplierBp,
            long flatFeeCents) {
    }
}
