package com.truecost.aggregate;

import static org.assertj.core.api.Assertions.assertThat;

import com.truecost.cache.DataFreshness;
import com.truecost.domain.Money;
import com.truecost.provider.hotel.HotelOffer;
import org.junit.jupiter.api.Test;

class TripPlannerHotelFreshnessTest {

    private static HotelOffer offer(String source) {
        return new HotelOffer("Test Hotel", Money.ofCents(30000), 2, source);
    }

    @Test
    void syntheticHotelIsNeverReportedFresh() {
        assertThat(TripPlanner.hotelFreshness(offer("SYNTHETIC"), DataFreshness.FRESH))
                .isEqualTo(DataFreshness.UNAVAILABLE);
        assertThat(TripPlanner.hotelFreshness(offer("SYNTHETIC"), DataFreshness.STALE))
                .isEqualTo(DataFreshness.UNAVAILABLE);
    }

    @Test
    void liveHotelKeepsItsCacheFreshness() {
        assertThat(TripPlanner.hotelFreshness(offer("AMADEUS"), DataFreshness.FRESH))
                .isEqualTo(DataFreshness.FRESH);
        assertThat(TripPlanner.hotelFreshness(offer("AMADEUS"), DataFreshness.STALE))
                .isEqualTo(DataFreshness.STALE);
    }

    @Test
    void missingHotelKeepsTheCacheFreshness() {
        assertThat(TripPlanner.hotelFreshness(null, DataFreshness.UNAVAILABLE))
                .isEqualTo(DataFreshness.UNAVAILABLE);
    }
}
