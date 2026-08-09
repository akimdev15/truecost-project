package com.truecost.toll;

/** The point along a route where the polyline first transitions from outside to inside the congestion zone. */
record ZoneEntry(double cumulativeDurationSeconds) {
}
