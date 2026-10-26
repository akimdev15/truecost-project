package com.truecost.api.dto;

import java.time.LocalDate;

/**
 * Which seeded reference data a response was computed against. snapshotDate is when the seed was
 * verified against its sources. latestTollRateEffectiveDate covers only the toll rate rows in force
 * for the crossings on this route, and latestTollProgramEffectiveDate only the programs of the
 * companies in the response, so the dates describe the rows actually used rather than the whole
 * table. Each is null when nothing of that kind was used.
 */
public record RateSnapshot(
        LocalDate snapshotDate,
        LocalDate latestTollRateEffectiveDate,
        LocalDate latestTollProgramEffectiveDate) {
}
