package com.truecost.toll.strategy;

import java.time.LocalDate;

/**
 * One toll product a rental company offers, sourced from the toll_program seed table. feeCapped
 * plus feeCapCents model the admin fee cap as a boolean plus a value rather than a sentinel
 * number, so a legitimate cap of zero cents is distinct from no cap, and both cap fields plus
 * tollRateBasis are inert for UNLIMITED_DAILY.
 */
public record TollProgram(
        String companyName,
        String programName,
        ProgramType type,
        long dailyFeeCents,
        boolean feeCapped,
        long feeCapCents,
        TollRateBasis tollRateBasis,
        boolean coversCongestion,
        LocalDate effectiveDate) {
}
