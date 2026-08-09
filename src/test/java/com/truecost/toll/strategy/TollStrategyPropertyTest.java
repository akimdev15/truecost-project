package com.truecost.toll.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Runs the ten thousand randomized scenarios docs/design/toll-strategy-engine.md section 7
 * requires, asserting the engine's winner, winning program name, and total agree with the
 * brute-force oracle in every case. Each scenario is deterministic from MASTER_SEED plus its
 * case index, so a failure reproduces exactly by rerunning that single index through
 * StrategyScenarioGenerator.generate.
 */
class TollStrategyPropertyTest {

    private static final long MASTER_SEED = 8_675_309L;
    private static final int CASE_COUNT = 10_000;

    @Test
    void engineMatchesTheBruteForceOracleAcrossTenThousandRandomizedScenarios() {
        for (int caseIndex = 0; caseIndex < CASE_COUNT; caseIndex++) {
            StrategyInput input = StrategyScenarioGenerator.generate(MASTER_SEED, caseIndex);

            StrategyResult engineResult = TollStrategyEngine.evaluate(input);
            StrategyOracle.OracleResult oracleResult = StrategyOracle.solve(input);

            String context = "case index " + caseIndex + " with master seed " + MASTER_SEED;
            assertThat(engineResult.winner()).as(context).isEqualTo(oracleResult.winner());
            assertThat(engineResult.programName()).as(context).isEqualTo(oracleResult.programName());
            assertThat(engineResult.totalCents()).as(context).isEqualTo(oracleResult.totalCents());
        }
    }
}
