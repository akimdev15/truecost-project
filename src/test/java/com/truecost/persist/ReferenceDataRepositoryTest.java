package com.truecost.persist;

import static org.assertj.core.api.Assertions.assertThat;

import com.truecost.seed.SeedLoader;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Proves the Phase 1 reference data seed loads against a real Postgres and that the rate
 * lookup in TollRateRepository resolves the correct row, including the peak versus off-peak
 * boundary and the effective-date supersession case documented in
 * docs/design/reference-data-schema.md section 8. Fixture amounts here are the real amounts
 * seeded from data/seeds/toll_rates.csv, not the design doc's illustrative placeholder numbers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReferenceDataRepositoryTest {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

    @Autowired
    private SeedLoader seedLoader;

    @Autowired
    private TollCrossingRepository crossingRepository;

    @Autowired
    private TollProgramRepository programRepository;

    @Autowired
    private RentalCompanyRepository companyRepository;

    @Autowired
    private TollRateRepository rateRepository;

    @Autowired
    private VehicleClassRepository vehicleClassRepository;

    @BeforeAll
    void seedOnce() {
        seedLoader.loadAll();
    }

    @Test
    void seedsAtLeastFifteenCrossingsAndSixProgramsAcrossFiveCompanies() {
        assertThat(crossingRepository.count()).isGreaterThanOrEqualTo(15);
        assertThat(programRepository.count()).isGreaterThanOrEqualTo(6);
        assertThat(companyRepository.count()).isGreaterThanOrEqualTo(5);
        assertThat(programRepository.countDistinctCompanies()).isGreaterThanOrEqualTo(5);
    }

    @Test
    void flatCrossingResolvesSameRateForEveryVehicleClassMappingToPassenger() {
        Instant anyTime = instantAt(2026, 1, 7, 12, 0);

        assertThat(rateRepository.findRateCents("RFK", "MIDSIZE", "EZPASS", anyTime)).contains(746L);
        assertThat(rateRepository.findRateCents("RFK", "SUV", "EZPASS", anyTime)).contains(746L);
    }

    @Test
    void gwbMorningPeakBoundaryPricesDifferentlyOnEachSide() {
        Instant justBeforeSix = instantAt(2026, 1, 7, 5, 59);
        Instant atSix = instantAt(2026, 1, 7, 6, 0);

        assertThat(rateRepository.findRateCents("GWB", "MIDSIZE", "EZPASS", justBeforeSix)).contains(1479L);
        assertThat(rateRepository.findRateCents("GWB", "MIDSIZE", "EZPASS", atSix)).contains(1679L);
    }

    @Test
    void gwbEveningPeakBoundaryPricesDifferentlyOnEachSide() {
        Instant justBeforeEight = instantAt(2026, 1, 7, 19, 59);
        Instant atEight = instantAt(2026, 1, 7, 20, 0);

        assertThat(rateRepository.findRateCents("GWB", "MIDSIZE", "EZPASS", justBeforeEight)).contains(1679L);
        assertThat(rateRepository.findRateCents("GWB", "MIDSIZE", "EZPASS", atEight)).contains(1479L);
    }

    @Test
    void gwbEffectiveDateSupersessionSelectsTheNewerRow() {
        Instant beforeTariffChange = instantAt(2025, 7, 1, 7, 0);
        Instant afterTariffChange = instantAt(2026, 3, 1, 14, 0);

        assertThat(rateRepository.findRateCents("GWB", "MIDSIZE", "EZPASS", beforeTariffChange)).contains(1606L);
        assertThat(rateRepository.findRateCents("GWB", "MIDSIZE", "EZPASS", afterTariffChange)).contains(1679L);
    }

    @Test
    void vehicleClassDefaultMpgIsAvailableForFuelCostService() {
        assertThat(vehicleClassRepository.findDefaultMpg("MIDSIZE")).isEqualTo(30.5);
    }

    private static Instant instantAt(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, NEW_YORK).toInstant();
    }
}
