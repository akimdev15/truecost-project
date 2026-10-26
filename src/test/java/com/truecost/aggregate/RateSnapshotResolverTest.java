package com.truecost.aggregate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.truecost.api.dto.RateSnapshot;
import com.truecost.persist.ReferenceSnapshotRepository;
import com.truecost.persist.TollRateRepository;
import com.truecost.toll.strategy.ProgramType;
import com.truecost.toll.strategy.TollProgram;
import com.truecost.toll.strategy.TollRateBasis;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RateSnapshotResolverTest {

    private static final LocalDate TRIP_DATE = LocalDate.of(2026, 7, 24);

    private final ReferenceSnapshotRepository snapshotRepository = mock(ReferenceSnapshotRepository.class);
    private final TollRateRepository tollRateRepository = mock(TollRateRepository.class);
    private final RateSnapshotResolver resolver = new RateSnapshotResolver(snapshotRepository, tollRateRepository);

    private static TollProgram program(LocalDate effectiveDate) {
        return new TollProgram("Hertz", "PlatePass", ProgramType.PER_CROSSING_USAGE_DAY, 999L, false, 0L,
                TollRateBasis.MAX_CASH, false, effectiveDate);
    }

    @Test
    void reportsTheSeededSnapshotAndTheLatestDatesOfTheRowsUsed() {
        when(snapshotRepository.findSnapshotDate()).thenReturn(Optional.of(LocalDate.of(2026, 7, 17)));
        when(tollRateRepository.findLatestEffectiveDate(any(), any()))
                .thenReturn(Optional.of(LocalDate.of(2026, 1, 4)));

        RateSnapshot snapshot = resolver.resolve(Set.of("GWB"), TRIP_DATE,
                List.of(program(LocalDate.of(2026, 3, 1)), program(LocalDate.of(2026, 7, 17))));

        assertThat(snapshot).isEqualTo(new RateSnapshot(
                LocalDate.of(2026, 7, 17), LocalDate.of(2026, 1, 4), LocalDate.of(2026, 7, 17)));
    }

    @Test
    void leavesTheRateDateNullWhenNoCrossingWasPriced() {
        when(snapshotRepository.findSnapshotDate()).thenReturn(Optional.of(LocalDate.of(2026, 7, 17)));

        RateSnapshot snapshot = resolver.resolve(Set.of(), TRIP_DATE, List.of());

        assertThat(snapshot.latestTollRateEffectiveDate()).isNull();
        assertThat(snapshot.latestTollProgramEffectiveDate()).isNull();
        verifyNoInteractions(tollRateRepository);
    }
}
