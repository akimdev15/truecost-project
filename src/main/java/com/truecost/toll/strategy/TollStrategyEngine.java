package com.truecost.toll.strategy;

import com.truecost.toll.PricedCrossing;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Chooses, for one rental option and one route, whether the user's own personal E-ZPass, a
 * rental company per-crossing toll program, or a company unlimited toll plan is the cheapest way
 * to pay for the tolls and congestion of a trip. The engine never evaluates a personal tag
 * combined with a company unlimited plan, since the plan already covers every toll and any
 * congestion it includes, so the tag can at best sit idle and at worst double bill a crossing,
 * making the combination weakly dominated by the better of the two standalone strategies and
 * therefore excluded from the candidate list by design.
 */
public final class TollStrategyEngine {

    private TollStrategyEngine() {
    }

    public static StrategyResult evaluate(StrategyInput input) {
        long tollEz = 0L;
        long tollMax = 0L;
        Set<LocalDate> usageDates = new LinkedHashSet<>();
        for (PricedCrossing crossing : input.crossings()) {
            tollEz += crossing.ezpassCents();
            tollMax += crossing.cashOrMailCents();
            usageDates.add(crossing.tollDate());
        }
        int usageDays = usageDates.size();

        long congEz = 0L;
        long congMail = 0L;
        for (CongestionDay day : input.congestionDays()) {
            congEz += day.netEzpassCents();
            congMail += day.netMailCents();
        }

        boolean hasTolls = !input.crossings().isEmpty();
        boolean hasCongestionCost = congEz > 0 || congMail > 0;

        List<Candidate> candidates = new ArrayList<>();

        if (input.ownsPersonalTag()) {
            candidates.add(personalTagCandidate(tollEz, congEz));
        }

        for (TollProgram program : input.programs()) {
            if (program.type() == ProgramType.UNLIMITED_DAILY) {
                candidates.add(unlimitedCandidate(program, congMail, input.rentalDays()));
            } else {
                candidates.add(perCrossingCandidate(program, tollEz, tollMax, congMail, usageDays,
                        input.rentalDays()));
            }
        }

        if (!hasTolls && !hasCongestionCost) {
            candidates.add(noArrangementCandidate());
        }

        candidates.sort(TIE_BREAK);

        Candidate winner = candidates.get(0);
        Candidate runnerUp = candidates.size() > 1 ? candidates.get(1) : null;

        List<StrategyCost> costs = candidates.stream().map(Candidate::cost).toList();
        String explanation = explain(winner, runnerUp, congMail);

        return new StrategyResult(winner.strategy(), winner.cost().programName(),
                winner.cost().totalCents(), explanation, costs);
    }

    private static Candidate personalTagCandidate(long tollEz, long congEz) {
        long total = tollEz + congEz;
        StrategyCost cost = new StrategyCost(Strategy.PERSONAL_TAG, null, total, 0L, tollEz, congEz, 0);
        return new Candidate(Strategy.PERSONAL_TAG, null, cost);
    }

    private static Candidate noArrangementCandidate() {
        StrategyCost cost = new StrategyCost(Strategy.NO_ARRANGEMENT, null, 0L, 0L, 0L, 0L, 0);
        return new Candidate(Strategy.NO_ARRANGEMENT, null, cost);
    }

    private static Candidate perCrossingCandidate(TollProgram program, long tollEz, long tollMax,
            long congMail, int usageDays, int rentalDays) {
        int feeDays = program.type() == ProgramType.PER_CROSSING_USAGE_DAY
                ? usageDays
                : (usageDays > 0 ? rentalDays : 0);
        long rawFee = program.dailyFeeCents() * feeDays;
        long feeCents = program.feeCapped() ? Math.min(rawFee, program.feeCapCents()) : rawFee;
        long tollCents = program.tollRateBasis() == TollRateBasis.EZPASS ? tollEz : tollMax;
        long congestionCents = program.coversCongestion() ? 0L : congMail;
        long total = feeCents + tollCents + congestionCents;
        StrategyCost cost = new StrategyCost(Strategy.COMPANY_PER_CROSSING, program.programName(),
                total, feeCents, tollCents, congestionCents, feeDays);
        return new Candidate(Strategy.COMPANY_PER_CROSSING, program, cost);
    }

    private static Candidate unlimitedCandidate(TollProgram program, long congMail, int rentalDays) {
        long feeCents = program.dailyFeeCents() * rentalDays;
        long congestionCents = program.coversCongestion() ? 0L : congMail;
        long total = feeCents + congestionCents;
        StrategyCost cost = new StrategyCost(Strategy.COMPANY_UNLIMITED, program.programName(),
                total, feeCents, 0L, congestionCents, rentalDays);
        return new Candidate(Strategy.COMPANY_UNLIMITED, program, cost);
    }

    private static int priority(Strategy strategy) {
        return switch (strategy) {
            case NO_ARRANGEMENT -> 0;
            case PERSONAL_TAG -> 1;
            case COMPANY_PER_CROSSING -> 2;
            case COMPANY_UNLIMITED -> 3;
        };
    }

    private static final Comparator<Candidate> TIE_BREAK =
            Comparator.comparingLong((Candidate c) -> c.cost().totalCents())
                    .thenComparingInt(c -> priority(c.strategy()))
                    .thenComparing(c -> c.cost().programName(),
                            Comparator.nullsFirst(Comparator.naturalOrder()));

    /**
     * When only one candidate is evaluated, no personal tag and exactly one company program,
     * there is no runner up so a plain single-option sentence is used instead. The property test
     * asserts only the winner and the total, never this string, so its wording is free to change.
     */
    private static String explain(Candidate winner, Candidate runnerUp, long congMail) {
        String winnerLabel = label(winner);
        String winnerTotal = money(winner.cost().totalCents());

        if (runnerUp == null) {
            return winnerLabel + " wins at " + winnerTotal
                    + " as the only toll strategy evaluated for this rental option.";
        }

        String runnerLabel = label(runnerUp);
        String runnerTotal = money(runnerUp.cost().totalCents());
        long margin = runnerUp.cost().totalCents() - winner.cost().totalCents();

        return winnerLabel + " wins at " + winnerTotal + ". Next best is " + runnerLabel + " at "
                + runnerTotal + ", a margin of " + money(margin) + ". "
                + factorSentence(winner, runnerUp, congMail);
    }

    private static String factorSentence(Candidate winner, Candidate runnerUp, long congMail) {
        if (winner.strategy() == Strategy.NO_ARRANGEMENT) {
            return "This route crosses no tolled facility and enters no congestion zone, "
                    + "so no toll product and no personal tag are needed.";
        }

        if (winner.strategy() == Strategy.COMPANY_PER_CROSSING) {
            TollProgram program = winner.program();
            long rawFee = program.dailyFeeCents() * winner.cost().feeDays();
            if (program.feeCapped() && rawFee > program.feeCapCents()) {
                return "The program admin fee reached its cap of " + money(program.feeCapCents())
                        + ", so extra toll days add no fee, which is what makes it cheapest.";
            }
        }

        if (winner.strategy() == Strategy.COMPANY_PER_CROSSING
                || winner.strategy() == Strategy.COMPANY_UNLIMITED) {
            TollProgram program = winner.program();
            long currentCongestion = winner.cost().congestionCents();
            long flippedCongestion = program.coversCongestion() ? congMail : 0L;
            long flippedTotal = winner.cost().totalCents() - currentCongestion + flippedCongestion;
            boolean originallyBeats = winner.cost().totalCents() <= runnerUp.cost().totalCents();
            boolean stillBeats = flippedTotal <= runnerUp.cost().totalCents();
            if (originallyBeats != stillBeats) {
                return "The plan covers the congestion charge of " + money(congMail)
                        + ", which is the deciding factor against the runner up.";
            }
        }

        if (winner.strategy() == Strategy.COMPANY_UNLIMITED) {
            return "The flat unlimited plan fee of " + money(winner.cost().feeCents())
                    + " covers every toll, which comes out cheaper than paying per crossing here.";
        }

        return "The toll total of " + money(winner.cost().tollCents())
                + " is low enough that avoiding any daily plan fee is what wins.";
    }

    private static String label(Candidate candidate) {
        return switch (candidate.strategy()) {
            case PERSONAL_TAG -> "Personal E-ZPass";
            case NO_ARRANGEMENT -> "No toll arrangement";
            case COMPANY_PER_CROSSING -> companyLabel(candidate.program()) + " per-crossing";
            case COMPANY_UNLIMITED -> companyLabel(candidate.program());
        };
    }

    private static String companyLabel(TollProgram program) {
        return program.companyName() + " " + program.programName();
    }

    private static String money(long cents) {
        return "%d.%02d".formatted(cents / 100, cents % 100);
    }

    private record Candidate(Strategy strategy, TollProgram program, StrategyCost cost) {
    }
}
