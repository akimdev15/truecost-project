package com.truecost.toll.strategy;

/**
 * The single discriminator for a toll program's cost shape. Deliberately folds program type and
 * fee basis into one field so invalid combinations, such as an unlimited program with a per-day
 * fee basis, cannot be represented.
 */
public enum ProgramType {
    PER_CROSSING_USAGE_DAY,
    PER_CROSSING_ALL_DAYS,
    UNLIMITED_DAILY
}
