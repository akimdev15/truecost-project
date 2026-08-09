package com.truecost.toll;

import com.truecost.route.Route;
import com.truecost.route.RoutePoint;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Finds whether a route polyline enters the Congestion Relief Zone, and if so, where. A route
 * that starts inside the zone and only ever leaves is not treated as entering, matching the MTA's
 * gantry based detection where a vehicle already inside is not charged on the way out, and only
 * the first entry matters since the charge is levied once per calendar day.
 */
@Component
public class CongestionZoneDetector {

    Optional<ZoneEntry> detectEntry(Route route) {
        List<RoutePoint> points = route.points();
        if (points.isEmpty()) {
            return Optional.empty();
        }

        boolean previouslyInside = CongestionZone.contains(points.get(0).lat(), points.get(0).lng());
        for (int i = 1; i < points.size(); i++) {
            RoutePoint point = points.get(i);
            boolean inside = CongestionZone.contains(point.lat(), point.lng());
            if (inside && !previouslyInside) {
                return Optional.of(new ZoneEntry(point.cumulativeDurationSeconds()));
            }
            previouslyInside = inside;
        }
        return Optional.empty();
    }
}
