package com.truecost.provider.rental;

import static org.assertj.core.api.Assertions.assertThat;

import com.truecost.provider.ProviderResult;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Proves the determinism acceptance criterion from PLAN.md Phase 4: two calls with an identical
 * RentalQuoteRequest against the same provider instance return equal quote lists, which is what
 * makes k6 load runs and accuracy tests reproducible against the placeholder calibration data in
 * data/seeds/rental_calibration.csv.
 */
class SyntheticRentalProviderTest {

    private final SyntheticRentalProvider provider = new SyntheticRentalProvider("data/seeds");

    @Test
    void identicalRequestsProduceEqualQuoteLists() {
        RentalQuoteRequest request = new RentalQuoteRequest(
                "JFK", LocalDate.of(2026, 8, 14), LocalDate.of(2026, 8, 16), "MIDSIZE");

        ProviderResult<List<RentalQuote>> first = provider.fetchQuotes(request);
        ProviderResult<List<RentalQuote>> second = provider.fetchQuotes(request);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void returnsOneQuotePerSeededCompanyForTheRequestedCarClass() {
        RentalQuoteRequest request = new RentalQuoteRequest(
                "EWR", LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 7), "SUV");

        List<RentalQuote> quotes = presentValue(provider.fetchQuotes(request));

        assertThat(quotes).hasSize(6);
        assertThat(quotes).allSatisfy(quote -> {
            assertThat(quote.carClassCode()).isEqualTo("SUV");
            assertThat(quote.rentalDays()).isEqualTo(3);
            assertThat(quote.source()).isEqualTo("SYNTHETIC");
            assertThat(quote.totalCost().cents()).isPositive();
        });
    }

    @Test
    void differentPickupLocationsCanProduceDifferentPricesForTheSameCompanyAndClass() {
        RentalQuoteRequest jfk = new RentalQuoteRequest(
                "JFK", LocalDate.of(2026, 8, 14), LocalDate.of(2026, 8, 16), "MIDSIZE");
        RentalQuoteRequest ewr = new RentalQuoteRequest(
                "EWR", LocalDate.of(2026, 8, 14), LocalDate.of(2026, 8, 16), "MIDSIZE");

        List<RentalQuote> jfkQuotes = presentValue(provider.fetchQuotes(jfk));
        List<RentalQuote> ewrQuotes = presentValue(provider.fetchQuotes(ewr));

        assertThat(jfkQuotes).isNotEqualTo(ewrQuotes);
    }

    @Test
    void absentWhenReturnDateIsNotAfterPickupDate() {
        RentalQuoteRequest sameDayRequest = new RentalQuoteRequest(
                "JFK", LocalDate.of(2026, 8, 14), LocalDate.of(2026, 8, 14), "MIDSIZE");

        assertThat(provider.fetchQuotes(sameDayRequest)).isInstanceOf(ProviderResult.Absent.class);
    }

    @Test
    void absentWhenCarClassHasNoCalibrationRow() {
        RentalQuoteRequest request = new RentalQuoteRequest(
                "JFK", LocalDate.of(2026, 8, 14), LocalDate.of(2026, 8, 16), "NOT_A_REAL_CLASS");

        assertThat(provider.fetchQuotes(request)).isInstanceOf(ProviderResult.Absent.class);
    }

    private static List<RentalQuote> presentValue(ProviderResult<List<RentalQuote>> result) {
        return switch (result) {
            case ProviderResult.Present<List<RentalQuote>> present -> present.value();
            case ProviderResult.Absent<List<RentalQuote>> absent ->
                    throw new AssertionError("expected a present result, reason=" + absent.reason());
        };
    }
}
