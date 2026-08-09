package com.truecost.cache;

/**
 * FRESH means the value was served inside its logical TTL, STALE means it was served from the
 * stale-while-revalidate window with a background refresh already triggered. TwoTierCache itself
 * never returns UNAVAILABLE, callers use it to mark an unrecoverable load failure.
 */
public enum DataFreshness {
    FRESH,
    STALE,
    UNAVAILABLE
}
