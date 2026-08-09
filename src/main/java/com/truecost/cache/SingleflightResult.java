package com.truecost.cache;

/** value is shared by every caller coalesced onto one load. coalesced is false only for the leader. */
public record SingleflightResult<T>(T value, boolean coalesced) {
}
