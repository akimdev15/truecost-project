package com.truecost.toll.strategy;

import com.truecost.toll.PricedCrossing;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A brute-force, independently written reimplementation of the toll strategy engine's rules,
 * used only as the property test oracle per docs/design/toll-strategy-engine.md section 7. This
 * class shares no code with TollStrategyEngine, calls none of its methods, and imports none of
 * its internal helpers. It reads only the design document, not the engine source, recomputes the
 * usage-day count, the toll totals, and the congestion totals directly from the input lists
 * inside its own loop, and applies each cost formula as the most literal possible transcription
 * of the English rules in section 2, with every term inlined. The only place it deliberately
 * agrees with the engine is the section 4 tie-break total order, which both implementations must
 * apply identically for the property test to be meaningful.
 */
final class StrategyOracle {

    private StrategyOracle() {
    }

    record OracleResult(Strategy winner, String programName, long totalCents) {
    }

    static OracleResult solve(StrategyInput input) {
        long tollEzSum = 0L;
        for (PricedCrossing crossing : input.crossings()) {
            tollEzSum = tollEzSum + crossing.ezpassCents();
        }

        long tollMaxSum = 0L;
        for (PricedCrossing crossing : input.crossings()) {
            tollMaxSum = tollMaxSum + crossing.cashOrMailCents();
        }

        Set<LocalDate> distinctTollDates = new HashSet<>();
        for (PricedCrossing crossing : input.crossings()) {
            distinctTollDates.add(crossing.tollDate());
        }
        int usageDays = distinctTollDates.size();

        long congEzSum = 0L;
        for (CongestionDay day : input.congestionDays()) {
            congEzSum = congEzSum + day.netEzpassCents();
        }

        long congMailSum = 0L;
        for (CongestionDay day : input.congestionDays()) {
            congMailSum = congMailSum + day.netMailCents();
        }

        boolean hasTolls = input.crossings().size() > 0;
        boolean hasCongestionCost = congEzSum > 0 || congMailSum > 0;

        List<OracleCandidate> candidates = new ArrayList<>();

        if (input.ownsPersonalTag()) {
            long personalTagTotal = tollEzSum + congEzSum;
            candidates.add(new OracleCandidate(Strategy.PERSONAL_TAG, null, personalTagTotal));
        }

        for (TollProgram program : input.programs()) {
            if (program.type() == ProgramType.PER_CROSSING_USAGE_DAY) {
                int feeDays = usageDays;
                long rawFee = program.dailyFeeCents() * feeDays;
                long feeCents = program.feeCapped()
                        ? Math.min(rawFee, program.feeCapCents())
                        : rawFee;
                long tollCents = program.tollRateBasis() == TollRateBasis.EZPASS
                        ? tollEzSum
                        : tollMaxSum;
                long congestionCents = program.coversCongestion() ? 0L : congMailSum;
                long total = feeCents + tollCents + congestionCents;
                candidates.add(new OracleCandidate(Strategy.COMPANY_PER_CROSSING,
                        program.programName(), total));
            } else if (program.type() == ProgramType.PER_CROSSING_ALL_DAYS) {
                int feeDays;
                if (usageDays > 0) {
                    feeDays = input.rentalDays();
                } else {
                    feeDays = 0;
                }
                long rawFee = program.dailyFeeCents() * feeDays;
                long feeCents = program.feeCapped()
                        ? Math.min(rawFee, program.feeCapCents())
                        : rawFee;
                long tollCents = program.tollRateBasis() == TollRateBasis.EZPASS
                        ? tollEzSum
                        : tollMaxSum;
                long congestionCents = program.coversCongestion() ? 0L : congMailSum;
                long total = feeCents + tollCents + congestionCents;
                candidates.add(new OracleCandidate(Strategy.COMPANY_PER_CROSSING,
                        program.programName(), total));
            } else if (program.type() == ProgramType.UNLIMITED_DAILY) {
                long feeCents = program.dailyFeeCents() * input.rentalDays();
                long congestionCents = program.coversCongestion() ? 0L : congMailSum;
                long total = feeCents + congestionCents;
                candidates.add(new OracleCandidate(Strategy.COMPANY_UNLIMITED,
                        program.programName(), total));
            }
        }

        if (!hasTolls && !hasCongestionCost) {
            candidates.add(new OracleCandidate(Strategy.NO_ARRANGEMENT, null, 0L));
        }

        OracleCandidate winner = candidates.get(0);
        for (OracleCandidate candidate : candidates) {
            if (isStrictlyBetter(candidate, winner)) {
                winner = candidate;
            }
        }

        return new OracleResult(winner.strategy, winner.programName, winner.totalCents);
    }

    private static boolean isStrictlyBetter(OracleCandidate a, OracleCandidate b) {
        if (a.totalCents != b.totalCents) {
            return a.totalCents < b.totalCents;
        }
        int priorityA = oraclePriority(a.strategy);
        int priorityB = oraclePriority(b.strategy);
        if (priorityA != priorityB) {
            return priorityA < priorityB;
        }
        if (a.programName == null || b.programName == null) {
            return false;
        }
        return a.programName.compareTo(b.programName) < 0;
    }

    private static int oraclePriority(Strategy strategy) {
        if (strategy == Strategy.NO_ARRANGEMENT) {
            return 0;
        }
        if (strategy == Strategy.PERSONAL_TAG) {
            return 1;
        }
        if (strategy == Strategy.COMPANY_PER_CROSSING) {
            return 2;
        }
        return 3;
    }

    private static final class OracleCandidate {
        private final Strategy strategy;
        private final String programName;
        private final long totalCents;

        private OracleCandidate(Strategy strategy, String programName, long totalCents) {
            this.strategy = strategy;
            this.programName = programName;
            this.totalCents = totalCents;
        }
    }
}
