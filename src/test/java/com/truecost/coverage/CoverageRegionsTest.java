package com.truecost.coverage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class CoverageRegionsTest {

    private static final Path SEED = Path.of("data/seeds/coverage_regions.csv");
    private static final Path GOLDEN_REQUEST = Path.of("eval/golden/sample-request.json");

    private final CoverageRegions seeded = new CoverageRegions(CoverageRegionsTest::readSeed);

    @Test
    void pointInsideARegionIsCovered() {
        CoverageRegions regions = single(40.0, -75.0, 41.0, -74.0);

        assertThat(regions.contains(40.5, -74.5)).isTrue();
    }

    @Test
    void pointOutsideEveryRegionIsNotCovered() {
        CoverageRegions regions = single(40.0, -75.0, 41.0, -74.0);

        assertThat(regions.contains(42.0, -74.5)).isFalse();
        assertThat(regions.contains(40.5, -73.0)).isFalse();
    }

    @Test
    void boundaryPointsAreCovered() {
        CoverageRegions regions = single(40.0, -75.0, 41.0, -74.0);

        assertThat(regions.contains(40.0, -75.0)).isTrue();
        assertThat(regions.contains(41.0, -74.0)).isTrue();
        assertThat(regions.contains(41.0001, -74.0)).isFalse();
    }

    @Test
    void pointCoveredByAnyOneRegionIsCovered() {
        CoverageRegions regions = new CoverageRegions(() -> List.of(
                new CoverageRegion("A", "A", 40.0, -75.0, 41.0, -74.0),
                new CoverageRegion("B", "B", 42.0, -72.0, 43.0, -71.0)));

        assertThat(regions.contains(42.5, -71.5)).isTrue();
        assertThat(regions.contains(41.5, -73.0)).isFalse();
    }

    @Test
    void emptySeedFailsLoudlyInsteadOfRejectingEverything() {
        CoverageRegions regions = new CoverageRegions(List::of);

        assertThatThrownBy(() -> regions.contains(40.0, -74.0)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sourceIsReadOnceAfterASuccessfulLoad() {
        int[] reads = {0};
        CoverageRegions regions = new CoverageRegions(() -> {
            reads[0]++;
            return List.of(new CoverageRegion("A", "A", 40.0, -75.0, 41.0, -74.0));
        });

        regions.contains(40.5, -74.5);
        regions.contains(40.6, -74.6);

        assertThat(reads[0]).isEqualTo(1);
    }

    @Test
    void seededRegionsAcceptEveryEvaluationSweepEndpointAndTheWaypointsBetween() {
        double[][] accepted = {
                {40.7505, -73.9934}, {40.6782, -73.9442},
                {39.9500, -75.1600}, {40.3573, -74.6672}, {41.7476, -74.0868},
                {41.0362, -71.9509}, {41.0534, -73.5387},
                {40.6868, -74.1651}, {40.2200, -74.7600}, {40.7000, -73.5000},
                {41.0300, -72.5000}, {41.2000, -74.1000}, {41.9300, -73.9900},
        };

        for (double[] p : accepted) {
            assertThat(seeded.contains(p[0], p[1])).as("%s,%s", p[0], p[1]).isTrue();
        }
    }

    @Test
    void seededRegionsRejectBostonWashingtonAndOtherDistantPoints() {
        assertThat(seeded.contains(42.3601, -71.0589)).as("Boston").isFalse();
        assertThat(seeded.contains(38.9072, -77.0369)).as("Washington DC").isFalse();
        assertThat(seeded.contains(41.3083, -72.9279)).as("New Haven").isFalse();
        assertThat(seeded.contains(42.6526, -73.7562)).as("Albany").isFalse();
    }

    @Test
    void goldenRequestEndpointsAreCovered() throws IOException {
        JsonNode golden = new ObjectMapper().readTree(Files.readString(GOLDEN_REQUEST));

        assertThat(seeded.contains(golden.get("originLat").asDouble(), golden.get("originLng").asDouble())).isTrue();
        assertThat(seeded.contains(golden.get("destLat").asDouble(), golden.get("destLng").asDouble())).isTrue();
    }

    private static CoverageRegions single(double minLat, double minLng, double maxLat, double maxLng) {
        return new CoverageRegions(() -> List.of(new CoverageRegion("R", "R", minLat, minLng, maxLat, maxLng)));
    }

    private static List<CoverageRegion> readSeed() {
        try {
            return Files.readAllLines(SEED).stream()
                    .skip(1)
                    .filter(line -> !line.isBlank())
                    .map(line -> line.split(",", -1))
                    .map(f -> new CoverageRegion(f[0], f[1], Double.parseDouble(f[2]), Double.parseDouble(f[3]),
                            Double.parseDouble(f[4]), Double.parseDouble(f[5])))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + SEED, e);
        }
    }
}
