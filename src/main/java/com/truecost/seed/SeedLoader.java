package com.truecost.seed;

import com.truecost.persist.CongestionCreditRepository;
import com.truecost.persist.CongestionScheduleRepository;
import com.truecost.persist.RentalCompanyRepository;
import com.truecost.persist.TollCrossingRepository;
import com.truecost.persist.TollProgramRepository;
import com.truecost.persist.TollRateRepository;
import com.truecost.persist.VehicleClassRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the reference data CSVs under data/seeds/ and loads them into Postgres. Truncate and
 * reload is the whole strategy, there are no dependent rows outside these tables yet, so a full
 * reset on every run is simpler and safer than a row-by-row upsert and makes reseeding after an
 * agency rate change a matter of editing a CSV and rerunning, not writing a migration.
 */
@Component
public class SeedLoader {

    private static final Logger log = LoggerFactory.getLogger(SeedLoader.class);

    private final JdbcClient jdbcClient;
    private final TollCrossingRepository crossingRepository;
    private final VehicleClassRepository vehicleClassRepository;
    private final RentalCompanyRepository companyRepository;
    private final TollRateRepository rateRepository;
    private final CongestionScheduleRepository congestionScheduleRepository;
    private final CongestionCreditRepository congestionCreditRepository;
    private final TollProgramRepository programRepository;
    private final Path seedDir;

    public SeedLoader(
            JdbcClient jdbcClient,
            TollCrossingRepository crossingRepository,
            VehicleClassRepository vehicleClassRepository,
            RentalCompanyRepository companyRepository,
            TollRateRepository rateRepository,
            CongestionScheduleRepository congestionScheduleRepository,
            CongestionCreditRepository congestionCreditRepository,
            TollProgramRepository programRepository,
            @Value("${truecost.seed.dir:data/seeds}") String seedDir) {
        this.jdbcClient = jdbcClient;
        this.crossingRepository = crossingRepository;
        this.vehicleClassRepository = vehicleClassRepository;
        this.companyRepository = companyRepository;
        this.rateRepository = rateRepository;
        this.congestionScheduleRepository = congestionScheduleRepository;
        this.congestionCreditRepository = congestionCreditRepository;
        this.programRepository = programRepository;
        this.seedDir = Path.of(seedDir);
    }

    @Transactional
    public SeedSummary loadAll() {
        truncateAll();

        int vehicleClasses = loadVehicleClasses();
        int crossings = loadCrossings();
        int tollRates = loadTollRates();
        int[] congestionCounts = loadCongestion();
        int tollPrograms = loadTollPrograms();
        int companies = companyRepository.count();

        SeedSummary summary = new SeedSummary(
                vehicleClasses, crossings, tollRates, congestionCounts[0], congestionCounts[1], companies, tollPrograms);

        log.info("seed loaded, vehicleClasses={} crossings={} tollRates={} congestionSchedules={} "
                        + "congestionCredits={} rentalCompanies={} tollPrograms={}",
                summary.vehicleClasses(), summary.crossings(), summary.tollRates(),
                summary.congestionSchedules(), summary.congestionCredits(), summary.rentalCompanies(),
                summary.tollPrograms());

        return summary;
    }

    private void truncateAll() {
        jdbcClient.sql("""
                        truncate table
                            toll_crossing, vehicle_class, rental_company,
                            toll_rate, congestion_schedule, congestion_credit, toll_program
                        restart identity cascade
                        """)
                .update();
    }

    private int loadVehicleClasses() {
        int count = 0;
        for (String[] f : rows("vehicle_classes.csv")) {
            vehicleClassRepository.insert(f[0], f[1], f[2], Double.parseDouble(f[3]));
            count++;
        }
        return count;
    }

    private int loadCrossings() {
        int count = 0;
        for (String[] f : rows("crossings.csv")) {
            crossingRepository.insert(f[0], f[1], f[2], f[3],
                    Double.parseDouble(f[4]), Double.parseDouble(f[5]), Integer.parseInt(f[6]), f[7]);
            count++;
        }
        return count;
    }

    private int loadTollRates() {
        int count = 0;
        for (String[] f : rows("toll_rates.csv")) {
            long crossingId = crossingRepository.findIdByCode(f[0])
                    .orElseThrow(() -> new IllegalStateException("toll_rates.csv references unknown crossing code " + f[0]));
            rateRepository.insert(crossingId, f[1], f[2], dayOfWeekMask(f[3]), f[4], f[5], Long.parseLong(f[6]), LocalDate.parse(f[7]));
            count++;
        }
        return count;
    }

    /** Returns {scheduleRowCount, creditRowCount}. */
    private int[] loadCongestion() {
        int scheduleCount = 0;
        int creditCount = 0;
        for (String[] f : rows("congestion.csv")) {
            String recordType = f[0];
            if ("SCHEDULE".equals(recordType)) {
                congestionScheduleRepository.insert(
                        f[3], f[1], f[2], dayOfWeekMask(f[4]), f[5], f[6], Long.parseLong(f[7]), Boolean.parseBoolean(f[8]), LocalDate.parse(f[11]));
                scheduleCount++;
            } else if ("CREDIT".equals(recordType)) {
                long crossingId = crossingRepository.findIdByCode(f[9])
                        .orElseThrow(() -> new IllegalStateException("congestion.csv CREDIT row references unknown crossing code " + f[9]));
                congestionCreditRepository.insert(crossingId, f[1], f[2], f[3], Long.parseLong(f[10]), LocalDate.parse(f[11]));
                creditCount++;
            } else {
                throw new IllegalStateException("congestion.csv has unknown record_type " + recordType);
            }
        }
        return new int[] {scheduleCount, creditCount};
    }

    private int loadTollPrograms() {
        int count = 0;
        for (String[] f : rows("toll_programs.csv")) {
            long companyId = companyRepository.upsert(f[0], f[1]);
            Long capCents = f[7].isBlank() ? null : Long.parseLong(f[7]);
            String tollRateBasis = f[9].isBlank() ? null : f[9];
            programRepository.insert(companyId, f[2], f[3], Long.parseLong(f[4]), f[5],
                    Boolean.parseBoolean(f[6]), capCents, Boolean.parseBoolean(f[8]), tollRateBasis,
                    Boolean.parseBoolean(f[10]), LocalDate.parse(f[11]));
            count++;
        }
        return count;
    }

    /** Monday-first seven character binary string to the Monday-bit-zero SMALLINT bitmask. */
    private static int dayOfWeekMask(String sevenCharBinary) {
        int mask = 0;
        for (int i = 0; i < 7; i++) {
            if (sevenCharBinary.charAt(i) == '1') {
                mask |= 1 << i;
            }
        }
        return mask;
    }

    private List<String[]> rows(String fileName) {
        Path file = seedDir.resolve(fileName);
        try {
            List<String> lines = Files.readAllLines(file);
            return lines.stream()
                    .skip(1)
                    .filter(line -> !line.isBlank())
                    .map(line -> line.split(",", -1))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read seed file " + file, e);
        }
    }
}
