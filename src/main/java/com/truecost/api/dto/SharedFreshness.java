package com.truecost.api.dto;

import com.truecost.cache.DataFreshness;

/**
 * The per field freshness of every shared, trip level data class. route and tolls each reflect
 * the worse of the two legs, STALE if either leg was served stale and neither was UNAVAILABLE,
 * UNAVAILABLE if either leg failed outright.
 */
public record SharedFreshness(DataFreshness route, DataFreshness tolls, DataFreshness fuel, DataFreshness hotel) {
}
