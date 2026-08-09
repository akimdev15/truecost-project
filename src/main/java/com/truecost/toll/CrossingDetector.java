package com.truecost.toll;

import com.truecost.persist.TollCrossingRepository;
import com.truecost.route.Route;
import com.truecost.route.RoutePoint;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Matches a decoded route polyline against seeded toll crossings by finding the nearest point
 * within threshold and comparing vehicle bearing to the crossing's tolled direction. A FORWARD
 * crossing tolls only when the bearing matches the stored travel bearing, not its reverse, while
 * a BOTH crossing tolls in either direction.
 */
@Component
public class CrossingDetector {

    private static final Logger log = LoggerFactory.getLogger(CrossingDetector.class);

    static final double DISTANCE_THRESHOLD_METERS = 50.0;
    static final double BEARING_TOLERANCE_DEGREES = 45.0;

    private final TollCrossingRepository crossingRepository;

    public CrossingDetector(TollCrossingRepository crossingRepository) {
        this.crossingRepository = crossingRepository;
    }

    /**
     * Detected crossings ordered by their position along the route, earliest first, which is
     * the order TollTimeline and the congestion pricer rely on when reasoning about which
     * crossing happens first in a trip.
     */
    public List<DetectedCrossing> detect(Route route) {
        List<TollCrossingRepository.CrossingRow> crossings = crossingRepository.findAll();
        List<DetectedCrossing> detected = new ArrayList<>();

        for (TollCrossingRepository.CrossingRow crossing : crossings) {
            detectOne(route, crossing).ifPresent(detected::add);
        }

        detected.sort(Comparator.comparingDouble(DetectedCrossing::cumulativeDurationSeconds));
        return detected;
    }

    private Optional<DetectedCrossing> detectOne(Route route, TollCrossingRepository.CrossingRow crossing) {
        List<RoutePoint> points = route.points();
        int closestIndex = -1;
        double closestDistance = Double.MAX_VALUE;

        for (int i = 0; i < points.size(); i++) {
            RoutePoint point = points.get(i);
            double distance = GeoMath.distanceMeters(point.lat(), point.lng(), crossing.latitude(), crossing.longitude());
            if (distance < closestDistance) {
                closestDistance = distance;
                closestIndex = i;
            }
        }

        if (closestIndex < 0 || closestDistance > DISTANCE_THRESHOLD_METERS) {
            return Optional.empty();
        }

        double vehicleBearing = vehicleBearingAt(points, closestIndex);
        boolean tolled = isTolled(vehicleBearing, crossing.travelBearingDeg(), crossing.tolledDirections());

        if (!tolled) {
            log.debug("crossing {} matched within threshold at {} meters but wrong direction, "
                            + "vehicleBearing={} travelBearingDeg={} tolledDirections={}",
                    crossing.code(), closestDistance, vehicleBearing, crossing.travelBearingDeg(),
                    crossing.tolledDirections());
            return Optional.empty();
        }

        log.info("crossing detected, code={} distanceMeters={} vehicleBearingDeg={}",
                crossing.code(), closestDistance, vehicleBearing);

        return Optional.of(new DetectedCrossing(
                crossing.code(),
                crossing.name(),
                crossing.crossingType(),
                closestDistance,
                vehicleBearing,
                points.get(closestIndex).cumulativeDurationSeconds()));
    }

    /**
     * The vehicle bearing at a polyline point, taken from the segment leaving the point when one
     * exists, falling back to the segment arriving at the point for the final point in the route.
     */
    private double vehicleBearingAt(List<RoutePoint> points, int index) {
        if (index < points.size() - 1) {
            RoutePoint here = points.get(index);
            RoutePoint next = points.get(index + 1);
            return GeoMath.bearingDegrees(here.lat(), here.lng(), next.lat(), next.lng());
        }
        RoutePoint previous = points.get(index - 1);
        RoutePoint here = points.get(index);
        return GeoMath.bearingDegrees(previous.lat(), previous.lng(), here.lat(), here.lng());
    }

    private boolean isTolled(double vehicleBearing, int travelBearingDeg, String tolledDirections) {
        double forwardDiff = GeoMath.circularDifferenceDegrees(vehicleBearing, travelBearingDeg);
        if ("BOTH".equals(tolledDirections)) {
            double reverseDiff = GeoMath.circularDifferenceDegrees(vehicleBearing, GeoMath.reverseBearing(travelBearingDeg));
            return forwardDiff <= BEARING_TOLERANCE_DEGREES || reverseDiff <= BEARING_TOLERANCE_DEGREES;
        }
        return forwardDiff <= BEARING_TOLERANCE_DEGREES;
    }
}
