package com.truecost.aggregate;

import com.truecost.api.dto.RateSnapshot;
import com.truecost.persist.ReferenceSnapshotRepository;
import com.truecost.persist.TollRateRepository;
import com.truecost.toll.strategy.TollProgram;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import org.springframework.stereotype.Component;

@Component
public class RateSnapshotResolver {

    private final ReferenceSnapshotRepository snapshotRepository;
    private final TollRateRepository tollRateRepository;

    public RateSnapshotResolver(ReferenceSnapshotRepository snapshotRepository,
            TollRateRepository tollRateRepository) {
        this.snapshotRepository = snapshotRepository;
        this.tollRateRepository = tollRateRepository;
    }

    public RateSnapshot resolve(Collection<String> crossingCodes, LocalDate tripDate,
            Collection<TollProgram> programs) {
        LocalDate rateDate = crossingCodes.isEmpty()
                ? null
                : tollRateRepository.findLatestEffectiveDate(crossingCodes, tripDate).orElse(null);
        LocalDate programDate = programs.stream()
                .map(TollProgram::effectiveDate)
                .max(Comparator.naturalOrder())
                .orElse(null);
        return new RateSnapshot(snapshotRepository.findSnapshotDate().orElse(null), rateDate, programDate);
    }
}
