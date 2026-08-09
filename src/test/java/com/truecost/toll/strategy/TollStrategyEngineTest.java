package com.truecost.toll.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.truecost.toll.PricedCrossing;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Curated tests for the toll strategy engine, per docs/design/toll-strategy-engine.md sections 8
 * and 9, plus branch coverage tests for every cost formula fork and tie-break key the ten
 * thousand case property test in TollStrategyPropertyTest exercises only statistically.
 */
class TollStrategyEngineTest {

    private static final LocalDate DAY = LocalDate.of(2026, 6, 5);

    @Test
    void boundaryACapFlipsTheWinner() {
        TollProgram programP = new TollProgram("Hertz", "PlatePass",
                ProgramType.PER_CROSSING_USAGE_DAY, 695, true, 1975, TollRateBasis.EZPASS, false,
                DAY);
        TollProgram programQ = new TollProgram("the", "unlimited plan", ProgramType.UNLIMITED_DAILY,
                999, false, 0, TollRateBasis.EZPASS, false, DAY);

        List<PricedCrossing> crossings = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            crossings.add(new PricedCrossing("X" + i, "Crossing " + i, DAY.plusDays(i), 600, 900));
        }

        StrategyInput input = new StrategyInput(crossings, List.of(), 5, false,
                List.of(programP, programQ));

        StrategyResult result = TollStrategyEngine.evaluate(input);

        assertThat(result.winner()).isEqualTo(Strategy.COMPANY_PER_CROSSING);
        assertThat(result.programName()).isEqualTo("PlatePass");
        assertThat(result.totalCents()).isEqualTo(4975);
        assertThat(result.explanation()).isEqualTo(
                "Hertz PlatePass per-crossing wins at 49.75. Next best is the unlimited plan at "
                        + "49.95, a margin of 0.20. The program admin fee reached its cap of 19.75, "
                        + "so extra toll days add no fee, which is what makes it cheapest.");
    }

    @Test
    void boundaryBCongestionCoverageFlipsTheWinner() {
        TollProgram programQ = new TollProgram("Avis", "e-Toll Unlimited",
                ProgramType.UNLIMITED_DAILY, 1300, false, 0, TollRateBasis.EZPASS, true, DAY);

        List<PricedCrossing> crossings = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            crossings.add(new PricedCrossing("X" + i, "Crossing " + i, DAY.plusDays(i), 900, 1200));
        }
        CongestionDay congestionDay = new CongestionDay(DAY, true, 900, 1350, null);

        StrategyInput input = new StrategyInput(crossings, List.of(congestionDay), 3, true,
                List.of(programQ));

        StrategyResult result = TollStrategyEngine.evaluate(input);

        assertThat(result.winner()).isEqualTo(Strategy.COMPANY_UNLIMITED);
        assertThat(result.totalCents()).isEqualTo(3900);
        assertThat(result.explanation()).isEqualTo(
                "Avis e-Toll Unlimited wins at 39.00. Next best is Personal E-ZPass at 45.00, a "
                        + "margin of 6.00. The plan covers the congestion charge of 13.50, which is "
                        + "the deciding factor against the runner up.");
    }

    @Test
    void boundaryCZeroTollSelectsNoArrangement() {
        TollProgram programP = new TollProgram("Hertz", "PlatePass",
                ProgramType.PER_CROSSING_USAGE_DAY, 695, true, 1975, TollRateBasis.EZPASS, false,
                DAY);
        TollProgram programQ = new TollProgram("Avis", "e-Toll Unlimited",
                ProgramType.UNLIMITED_DAILY, 999, false, 0, TollRateBasis.EZPASS, false, DAY);

        StrategyInput input = new StrategyInput(List.of(), List.of(), 2, true,
                List.of(programP, programQ));

        StrategyResult result = TollStrategyEngine.evaluate(input);

        assertThat(result.winner()).isEqualTo(Strategy.NO_ARRANGEMENT);
        assertThat(result.totalCents()).isEqualTo(0);
        assertThat(result.explanation()).isEqualTo(
                "No toll arrangement wins at 0.00. Next best is Personal E-ZPass at 0.00, a margin "
                        + "of 0.00. This route crosses no tolled facility and enters no congestion "
                        + "zone, so no toll product and no personal tag are needed.");
    }

    @Test
    void decisionBoundaryFigureShiftsTheWinnerAsRentalLengthGrowsWithUsageFixedAtTwo() {
        TollProgram programU = new TollProgram("Company", "U", ProgramType.PER_CROSSING_USAGE_DAY,
                695, true, 1975, TollRateBasis.EZPASS, false, DAY);
        TollProgram programA = new TollProgram("Company", "A", ProgramType.PER_CROSSING_ALL_DAYS,
                400, true, 2000, TollRateBasis.EZPASS, false, DAY);
        TollProgram programN = new TollProgram("Company", "N", ProgramType.UNLIMITED_DAILY, 1500,
                false, 0, TollRateBasis.EZPASS, true, DAY);
        List<TollProgram> programs = List.of(programU, programA, programN);

        assertRow(programs, 2, Strategy.COMPANY_UNLIMITED, "N", 3000);
        assertRow(programs, 3, Strategy.COMPANY_PER_CROSSING, "A", 4200);
        assertRow(programs, 4, Strategy.COMPANY_PER_CROSSING, "U", 4390);
        assertRow(programs, 5, Strategy.COMPANY_PER_CROSSING, "U", 4390);
        assertRow(programs, 7, Strategy.COMPANY_PER_CROSSING, "U", 4390);
    }

    private static void assertRow(List<TollProgram> programs, int rentalDays,
            Strategy expectedWinner, String expectedProgram, long expectedTotal) {
        LocalDate outbound = DAY;
        LocalDate returnDate = DAY.plusDays(rentalDays - 1);
        List<PricedCrossing> crossings = List.of(
                new PricedCrossing("OUT", "Outbound", outbound, 1500, 2000),
                new PricedCrossing("BACK", "Return", returnDate, 1500, 2000));

        StrategyInput input = new StrategyInput(crossings, List.of(), rentalDays, false, programs);

        StrategyResult result = TollStrategyEngine.evaluate(input);

        assertThat(result.winner()).as("rental days " + rentalDays).isEqualTo(expectedWinner);
        assertThat(result.programName()).as("rental days " + rentalDays).isEqualTo(expectedProgram);
        assertThat(result.totalCents()).as("rental days " + rentalDays).isEqualTo(expectedTotal);
    }

    @Test
    void maxCashBasisBillsTollsAtTheHigherRateAndSingleCandidateHasNoRunnerUp() {
        TollProgram program = new TollProgram("Thrifty", "TollPass",
                ProgramType.PER_CROSSING_USAGE_DAY, 500, false, 0, TollRateBasis.MAX_CASH, false,
                DAY);

        List<PricedCrossing> crossings = List.of(
                new PricedCrossing("X0", "Crossing 0", DAY, 1000, 1500),
                new PricedCrossing("X1", "Crossing 1", DAY.plusDays(1), 1000, 1500));

        StrategyInput input = new StrategyInput(crossings, List.of(), 2, false, List.of(program));

        StrategyResult result = TollStrategyEngine.evaluate(input);

        assertThat(result.winner()).isEqualTo(Strategy.COMPANY_PER_CROSSING);
        assertThat(result.totalCents()).isEqualTo(4000);
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).tollCents()).isEqualTo(3000);
        assertThat(result.explanation()).isEqualTo(
                "Thrifty TollPass per-crossing wins at 40.00 as the only toll strategy evaluated "
                        + "for this rental option.");
    }

    @Test
    void allRentalDaysProgramIsUntriggeredWhenNoTollIsIncurredEvenWithCongestionPresent() {
        TollProgram program = new TollProgram("Budget", "e-Toll", ProgramType.PER_CROSSING_ALL_DAYS,
                400, true, 2000, TollRateBasis.EZPASS, false, DAY);

        CongestionDay congestionDay = new CongestionDay(DAY, true, 500, 800, null);

        StrategyInput input = new StrategyInput(List.of(), List.of(congestionDay), 3, false,
                List.of(program));

        StrategyResult result = TollStrategyEngine.evaluate(input);

        assertThat(result.winner()).isEqualTo(Strategy.COMPANY_PER_CROSSING);
        assertThat(result.totalCents()).isEqualTo(800);
        assertThat(result.candidates().get(0).feeDays()).isEqualTo(0);
        assertThat(result.candidates().get(0).feeCents()).isEqualTo(0);
        assertThat(result.candidates().get(0).tollCents()).isEqualTo(0);
        assertThat(result.candidates().get(0).congestionCents()).isEqualTo(800);
    }

    @Test
    void perCrossingProgramThatCoversCongestionZeroesTheCongestionPortion() {
        TollProgram program = new TollProgram("Dollar", "AllInclusive",
                ProgramType.PER_CROSSING_USAGE_DAY, 300, false, 0, TollRateBasis.EZPASS, true, DAY);

        List<PricedCrossing> crossings = List.of(new PricedCrossing("X0", "Crossing 0", DAY, 1000,
                1200));
        CongestionDay congestionDay = new CongestionDay(DAY, true, 500, 800, null);

        StrategyInput input = new StrategyInput(crossings, List.of(congestionDay), 1, false,
                List.of(program));

        StrategyResult result = TollStrategyEngine.evaluate(input);

        assertThat(result.totalCents()).isEqualTo(1300);
        assertThat(result.candidates().get(0).congestionCents()).isEqualTo(0);
    }

    @Test
    void fallbackFactorIsTollTotalWhenThePersonalTagWinsOnLowTolls() {
        TollProgram program = new TollProgram("Budget", "eToll", ProgramType.PER_CROSSING_USAGE_DAY,
                1000, false, 0, TollRateBasis.EZPASS, false, DAY);

        List<PricedCrossing> crossings = List.of(new PricedCrossing("X0", "Crossing 0", DAY, 500,
                800));

        StrategyInput input = new StrategyInput(crossings, List.of(), 1, true, List.of(program));

        StrategyResult result = TollStrategyEngine.evaluate(input);

        assertThat(result.winner()).isEqualTo(Strategy.PERSONAL_TAG);
        assertThat(result.totalCents()).isEqualTo(500);
        assertThat(result.explanation()).isEqualTo(
                "Personal E-ZPass wins at 5.00. Next best is Budget eToll per-crossing at 15.00, a "
                        + "margin of 10.00. The toll total of 5.00 is low enough that avoiding any "
                        + "daily plan fee is what wins.");
    }

    @Test
    void tieBreakOnEqualTotalAndPrioritySelectsTheAlphabeticallyFirstProgramName() {
        TollProgram programBeta = new TollProgram("X", "Beta", ProgramType.UNLIMITED_DAILY, 1000,
                false, 0, TollRateBasis.EZPASS, true, DAY);
        TollProgram programAlpha = new TollProgram("X", "Alpha", ProgramType.UNLIMITED_DAILY, 1000,
                false, 0, TollRateBasis.EZPASS, true, DAY);

        List<PricedCrossing> crossings = List.of(new PricedCrossing("X0", "Crossing 0", DAY, 100,
                150));

        StrategyInput input = new StrategyInput(crossings, List.of(), 2, false,
                List.of(programBeta, programAlpha));

        StrategyResult result = TollStrategyEngine.evaluate(input);

        assertThat(result.programName()).isEqualTo("Alpha");
        assertThat(result.totalCents()).isEqualTo(2000);
    }

    @Test
    void noArrangementWinsWithoutAPersonalTagCandidateWhenTheUserOwnsNoTag() {
        TollProgram program = new TollProgram("Hertz", "PlatePass",
                ProgramType.PER_CROSSING_USAGE_DAY, 695, true, 1975, TollRateBasis.EZPASS, false,
                DAY);

        StrategyInput input = new StrategyInput(List.of(), List.of(), 1, false, List.of(program));

        StrategyResult result = TollStrategyEngine.evaluate(input);

        assertThat(result.winner()).isEqualTo(Strategy.NO_ARRANGEMENT);
        assertThat(result.candidates()).hasSize(2);
        assertThat(result.candidates().get(1).strategy()).isEqualTo(Strategy.COMPANY_PER_CROSSING);
    }

    @Test
    void perCrossingCongestionCoverageIsTheDecisiveFactorWhenTheCapDoesNotBind() {
        TollProgram program = new TollProgram("Dollar", "AllInclusive",
                ProgramType.PER_CROSSING_USAGE_DAY, 100, false, 0, TollRateBasis.EZPASS, true, DAY);

        List<PricedCrossing> crossings = List.of(new PricedCrossing("X0", "Crossing 0", DAY, 200,
                250));
        CongestionDay congestionDay = new CongestionDay(DAY, true, 1000, 1400, null);

        StrategyInput input = new StrategyInput(crossings, List.of(congestionDay), 1, true,
                List.of(program));

        StrategyResult result = TollStrategyEngine.evaluate(input);

        assertThat(result.winner()).isEqualTo(Strategy.COMPANY_PER_CROSSING);
        assertThat(result.totalCents()).isEqualTo(300);
        assertThat(result.explanation()).isEqualTo(
                "Dollar AllInclusive per-crossing wins at 3.00. Next best is Personal E-ZPass at "
                        + "12.00, a margin of 9.00. The plan covers the congestion charge of 14.00, "
                        + "which is the deciding factor against the runner up.");
    }
}
