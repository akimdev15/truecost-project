package com.truecost.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Decodes the canonical worked example from the Google encoded polyline algorithm format
 * specification, which OSRM's default geometries encoding uses, so a correct decode here proves
 * PolylineDecoder reads OSRM's wire format correctly independent of any live OSRM instance.
 */
class PolylineDecoderTest {

    @Test
    void decodesTheCanonicalGooglePolylineExample() {
        List<double[]> points = PolylineDecoder.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@");

        assertThat(points).hasSize(3);
        assertThat(points.get(0)[0]).isCloseTo(38.5, within(1e-5));
        assertThat(points.get(0)[1]).isCloseTo(-120.2, within(1e-5));
        assertThat(points.get(1)[0]).isCloseTo(40.7, within(1e-5));
        assertThat(points.get(1)[1]).isCloseTo(-120.95, within(1e-5));
        assertThat(points.get(2)[0]).isCloseTo(43.252, within(1e-5));
        assertThat(points.get(2)[1]).isCloseTo(-126.453, within(1e-5));
    }

    @Test
    void decodesAnEmptyStringToNoPoints() {
        assertThat(PolylineDecoder.decode("")).isEmpty();
    }
}
