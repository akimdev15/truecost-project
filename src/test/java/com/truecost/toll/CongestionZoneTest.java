package com.truecost.toll;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Sanity checks the simplified Congestion Relief Zone polygon documented in CongestionZone
 * against well known Manhattan landmarks clearly inside the zone, and points clearly outside it,
 * north of 60th Street, in the other boroughs, in New Jersey, and along the Hudson River corridor
 * the West Side Highway carve out approximates.
 */
class CongestionZoneTest {

    @Test
    void timesSquareIsInsideTheZone() {
        assertThat(CongestionZone.contains(40.7580, -73.9855)).isTrue();
    }

    @Test
    void wallStreetIsInsideTheZone() {
        assertThat(CongestionZone.contains(40.7074, -74.0113)).isTrue();
    }

    @Test
    void unionSquareIsInsideTheZone() {
        assertThat(CongestionZone.contains(40.7359, -73.9911)).isTrue();
    }

    @Test
    void oneTwentyFifthStreetNorthOfSixtiethIsOutsideTheZone() {
        assertThat(CongestionZone.contains(40.8116, -73.9465)).isFalse();
    }

    @Test
    void brooklynIsOutsideTheZone() {
        assertThat(CongestionZone.contains(40.6782, -73.9442)).isFalse();
    }

    @Test
    void newJerseyIsOutsideTheZone() {
        assertThat(CongestionZone.contains(40.7357, -74.1724)).isFalse();
    }

    @Test
    void theWestSideHighwayCorridorIsOutsideTheZone() {
        assertThat(CongestionZone.contains(40.7580, -74.0200)).isFalse();
    }

    @Test
    void theFdrDriveCorridorIsOutsideTheZone() {
        assertThat(CongestionZone.contains(40.7560, -73.9550)).isFalse();
    }
}
