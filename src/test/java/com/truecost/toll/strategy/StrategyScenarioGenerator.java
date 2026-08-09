package com.truecost.toll.strategy;

import com.truecost.toll.PricedCrossing;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Deterministically builds a randomized StrategyInput for one property test case, following the
 * generator parameter ranges in docs/design/toll-strategy-engine.md section 7. Each scenario is
 * derived from a master seed plus the case index alone, java.util.Random(masterSeed + caseIndex),
 * so any failing case reproduces exactly by rerunning that index.
 */
final class StrategyScenarioGenerator {

    private static final LocalDate WINDOW_START = LocalDate.of(2026, 3, 1);

    private StrategyScenarioGenerator() {
    }

    static StrategyInput generate(long masterSeed, int caseIndex) {
        Random rnd = new Random(masterSeed + caseIndex);

        int rentalDays = 1 + rnd.nextInt(7);
        boolean ownsPersonalTag = rnd.nextBoolean();

        List<PricedCrossing> crossings = generateCrossings(rnd, rentalDays);
        long tollEz = sumEzpass(crossings);
        long tollMax = sumMail(crossings);

        List<CongestionDay> congestionDays = generateCongestionDays(rnd, rentalDays);
        long congMail = sumNetMail(congestionDays);
        long congEz = sumNetEzpass(congestionDays);

        List<TollProgram> programs = generatePrograms(rnd, caseIndex, rentalDays);

        boolean nearTie = rnd.nextInt(5) == 0;
        if (nearTie) {
            programs = nearTieAdjust(programs, rnd, ownsPersonalTag, tollEz, tollMax, congEz,
                    congMail, rentalDays, crossings);
        }

        return new StrategyInput(crossings, congestionDays, rentalDays, ownsPersonalTag, programs);
    }

    private static List<PricedCrossing> generateCrossings(Random rnd, int rentalDays) {
        int crossingCount = rnd.nextInt(9);
        boolean fullCoverageShape = rnd.nextBoolean();

        List<PricedCrossing> crossings = new ArrayList<>();
        for (int i = 0; i < crossingCount; i++) {
            long ezpassCents = 50 + rnd.nextInt(1951);
            long cashOrMailCents = ezpassCents + rnd.nextInt(1501);
            int offset = fullCoverageShape ? (i % rentalDays) : rnd.nextInt(rentalDays);
            LocalDate tollDate = WINDOW_START.plusDays(offset);
            crossings.add(new PricedCrossing("X" + i, "Crossing " + i, tollDate, ezpassCents,
                    cashOrMailCents));
        }
        return crossings;
    }

    private static List<CongestionDay> generateCongestionDays(Random rnd, int rentalDays) {
        List<CongestionDay> congestionDays = new ArrayList<>();
        if (rnd.nextInt(10) >= 8) {
            return congestionDays;
        }

        int count = rnd.nextInt(rentalDays + 1);
        Set<Integer> offsets = new LinkedHashSet<>();
        for (int i = 0; i < count; i++) {
            offsets.add(rnd.nextInt(rentalDays));
        }

        for (int offset : offsets) {
            long netEzpassCents = rnd.nextInt(901);
            long netMailCents = netEzpassCents + rnd.nextInt(601);
            boolean peak = rnd.nextBoolean();
            congestionDays.add(new CongestionDay(WINDOW_START.plusDays(offset), peak,
                    netEzpassCents, netMailCents, null));
        }
        return congestionDays;
    }

    private static List<TollProgram> generatePrograms(Random rnd, int caseIndex, int rentalDays) {
        int programCount = 1 + rnd.nextInt(3);
        List<TollProgram> programs = new ArrayList<>();

        for (int i = 0; i < programCount; i++) {
            ProgramType type = ProgramType.values()[rnd.nextInt(3)];
            TollRateBasis basis = rnd.nextBoolean() ? TollRateBasis.EZPASS : TollRateBasis.MAX_CASH;
            boolean coversCongestion = rnd.nextBoolean();
            String companyName = "Company" + caseIndex;
            String programName = "Program" + caseIndex + "-" + i;

            if (type == ProgramType.UNLIMITED_DAILY) {
                long dailyFeeCents = 1000 + rnd.nextInt(1501);
                programs.add(new TollProgram(companyName, programName, type, dailyFeeCents, false,
                        0L, basis, coversCongestion, WINDOW_START));
            } else {
                long dailyFeeCents = 300 + rnd.nextInt(901);
                boolean capped = rnd.nextInt(10) < 7;
                long capCents = capped ? capValue(rnd, dailyFeeCents, rentalDays) : 0L;
                programs.add(new TollProgram(companyName, programName, type, dailyFeeCents, capped,
                        capCents, basis, coversCongestion, WINDOW_START));
            }
        }
        return programs;
    }

    private static long capValue(Random rnd, long dailyFeeCents, int rentalDays) {
        if (rnd.nextInt(10) < 4) {
            int plausibleDays = 1 + rnd.nextInt(rentalDays);
            long nearFee = dailyFeeCents * plausibleDays;
            long jitter = -300 + rnd.nextInt(601);
            return Math.max(0L, nearFee + jitter);
        }
        return 1000 + rnd.nextInt(3001);
    }

    /**
     * About one in five scenarios places two candidates within a few dollars of each other, since
     * uniform random parameters almost never land two strategies close together on their own, yet
     * the interesting flips and the tie-break live exactly at those near ties. The last generated
     * program's daily rate is nudged so its total lands near the personal tag total, when owned,
     * or near the first program's total otherwise.
     */
    private static List<TollProgram> nearTieAdjust(List<TollProgram> programs, Random rnd,
            boolean ownsPersonalTag, long tollEz, long tollMax, long congEz, long congMail,
            int rentalDays, List<PricedCrossing> crossings) {
        if (programs.isEmpty()) {
            return programs;
        }

        int usageDays = countDistinctTollDates(crossings);

        long targetTotal = ownsPersonalTag
                ? (tollEz + congEz)
                : candidateTotal(programs.get(0), tollEz, tollMax, congMail, usageDays, rentalDays);

        long jitter = -300 + rnd.nextInt(601);
        long desiredTotal = Math.max(0L, targetTotal + jitter);

        List<TollProgram> adjusted = new ArrayList<>(programs);
        int lastIndex = adjusted.size() - 1;
        TollProgram last = adjusted.get(lastIndex);
        long congestionComponent = last.coversCongestion() ? 0L : congMail;

        if (last.type() == ProgramType.UNLIMITED_DAILY) {
            long desiredFee = Math.max(0L, desiredTotal - congestionComponent);
            long perDay = Math.max(50L, desiredFee / Math.max(1, rentalDays));
            adjusted.set(lastIndex, withDailyFee(last, perDay));
        } else {
            int feeDays = last.type() == ProgramType.PER_CROSSING_USAGE_DAY
                    ? usageDays
                    : (usageDays > 0 ? rentalDays : 0);
            if (feeDays > 0) {
                long tollComponent = last.tollRateBasis() == TollRateBasis.EZPASS ? tollEz : tollMax;
                long desiredFee = Math.max(0L, desiredTotal - tollComponent - congestionComponent);
                long perDay = Math.max(50L, desiredFee / feeDays);
                TollProgram withFee = withDailyFee(last, perDay);
                long adjustedCap = withFee.feeCapped()
                        ? Math.max(withFee.feeCapCents(), perDay * feeDays)
                        : withFee.feeCapCents();
                adjusted.set(lastIndex, new TollProgram(withFee.companyName(), withFee.programName(),
                        withFee.type(), withFee.dailyFeeCents(), withFee.feeCapped(), adjustedCap,
                        withFee.tollRateBasis(), withFee.coversCongestion(),
                        withFee.effectiveDate()));
            }
        }

        return adjusted;
    }

    private static long candidateTotal(TollProgram program, long tollEz, long tollMax,
            long congMail, int usageDays, int rentalDays) {
        if (program.type() == ProgramType.UNLIMITED_DAILY) {
            long feeCents = program.dailyFeeCents() * rentalDays;
            long congestionCents = program.coversCongestion() ? 0L : congMail;
            return feeCents + congestionCents;
        }

        int feeDays = program.type() == ProgramType.PER_CROSSING_USAGE_DAY
                ? usageDays
                : (usageDays > 0 ? rentalDays : 0);
        long rawFee = program.dailyFeeCents() * feeDays;
        long feeCents = program.feeCapped() ? Math.min(rawFee, program.feeCapCents()) : rawFee;
        long tollCents = program.tollRateBasis() == TollRateBasis.EZPASS ? tollEz : tollMax;
        long congestionCents = program.coversCongestion() ? 0L : congMail;
        return feeCents + tollCents + congestionCents;
    }

    private static TollProgram withDailyFee(TollProgram program, long newDailyFeeCents) {
        return new TollProgram(program.companyName(), program.programName(), program.type(),
                newDailyFeeCents, program.feeCapped(), program.feeCapCents(),
                program.tollRateBasis(), program.coversCongestion(), program.effectiveDate());
    }

    private static int countDistinctTollDates(List<PricedCrossing> crossings) {
        Set<LocalDate> dates = new LinkedHashSet<>();
        for (PricedCrossing crossing : crossings) {
            dates.add(crossing.tollDate());
        }
        return dates.size();
    }

    private static long sumEzpass(List<PricedCrossing> crossings) {
        long sum = 0L;
        for (PricedCrossing crossing : crossings) {
            sum += crossing.ezpassCents();
        }
        return sum;
    }

    private static long sumMail(List<PricedCrossing> crossings) {
        long sum = 0L;
        for (PricedCrossing crossing : crossings) {
            sum += crossing.cashOrMailCents();
        }
        return sum;
    }

    private static long sumNetEzpass(List<CongestionDay> days) {
        long sum = 0L;
        for (CongestionDay day : days) {
            sum += day.netEzpassCents();
        }
        return sum;
    }

    private static long sumNetMail(List<CongestionDay> days) {
        long sum = 0L;
        for (CongestionDay day : days) {
            sum += day.netMailCents();
        }
        return sum;
    }
}
