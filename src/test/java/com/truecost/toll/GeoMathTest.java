package com.truecost.toll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class GeoMathTest {

    @Test
    void distanceMetersIsZeroForTheSamePoint() {
        assertThat(GeoMath.distanceMeters(40.8517, -73.9527, 40.8517, -73.9527)).isEqualTo(0.0, within(0.001));
    }

    @Test
    void distanceMetersMatchesAKnownOneDegreeLatitudeSeparation() {
        // One degree of latitude is close to 111.32 km everywhere on the sphere.
        double distance = GeoMath.distanceMeters(40.0, -74.0, 41.0, -74.0);
        assertThat(distance).isCloseTo(111_195.0, within(500.0));
    }

    @Test
    void bearingDegreesIsZeroDueNorth() {
        assertThat(GeoMath.bearingDegrees(40.0, -74.0, 41.0, -74.0)).isCloseTo(0.0, within(0.5));
    }

    @Test
    void bearingDegreesIsNinetyDueEast() {
        assertThat(GeoMath.bearingDegrees(40.0, -74.0, 40.0, -73.0)).isCloseTo(90.0, within(1.0));
    }

    @Test
    void bearingDegreesIsOneEightyDueSouth() {
        assertThat(GeoMath.bearingDegrees(41.0, -74.0, 40.0, -74.0)).isCloseTo(180.0, within(0.5));
    }

    @Test
    void bearingDegreesIsTwoSeventyDueWest() {
        assertThat(GeoMath.bearingDegrees(40.0, -73.0, 40.0, -74.0)).isCloseTo(270.0, within(1.0));
    }

    @Test
    void circularDifferenceHandlesTheZeroThreeSixtyWraparound() {
        assertThat(GeoMath.circularDifferenceDegrees(10.0, 350.0)).isEqualTo(20.0, within(0.001));
        assertThat(GeoMath.circularDifferenceDegrees(350.0, 10.0)).isEqualTo(20.0, within(0.001));
    }

    @Test
    void circularDifferenceOfOppositeBearingsIsOneEighty() {
        assertThat(GeoMath.circularDifferenceDegrees(0.0, 180.0)).isEqualTo(180.0, within(0.001));
    }

    @Test
    void reverseBearingWrapsAcrossZero() {
        assertThat(GeoMath.reverseBearing(100.0)).isEqualTo(280.0, within(0.001));
        assertThat(GeoMath.reverseBearing(280.0)).isEqualTo(100.0, within(0.001));
    }
}
