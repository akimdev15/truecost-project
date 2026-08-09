package com.truecost.api;

import com.truecost.aggregate.TripPlanner;
import com.truecost.api.dto.RentalRateOverride;
import com.truecost.api.dto.TripPlanRequest;
import com.truecost.api.dto.TripPlanResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/v1/trips/plan. Validation is manual rather than Bean Validation annotations, since
 * the request record's fields need cross field checks, such as returnAt after departureAt, that
 * a single field annotation cannot express.
 */
@RestController
@RequestMapping("/api/v1/trips")
public class TripController {

    private final TripPlanner tripPlanner;

    public TripController(TripPlanner tripPlanner) {
        this.tripPlanner = tripPlanner;
    }

    @PostMapping("/plan")
    public TripPlanResponse plan(@RequestBody TripPlanRequest request) {
        validate(request);
        return tripPlanner.plan(request);
    }

    /**
     * carClass is required whenever rentalRateOverrides is non-empty, since a user-priced
     * request targets one specific car class rather than fanning out across every seeded class.
     */
    private void validate(TripPlanRequest request) {
        List<String> errors = new ArrayList<>();

        if (request.departureAt() == null || request.returnAt() == null) {
            errors.add("departureAt and returnAt are required");
        } else if (!request.returnAt().isAfter(request.departureAt())) {
            errors.add("returnAt must be strictly after departureAt");
        }

        if (!isValidLatitude(request.originLat())) {
            errors.add("originLat must be between -90 and 90");
        }
        if (!isValidLongitude(request.originLng())) {
            errors.add("originLng must be between -180 and 180");
        }
        if (!isValidLatitude(request.destLat())) {
            errors.add("destLat must be between -90 and 90");
        }
        if (!isValidLongitude(request.destLng())) {
            errors.add("destLng must be between -180 and 180");
        }
        if (isBlank(request.pickupLocationCode())) {
            errors.add("pickupLocationCode must not be blank");
        }
        if (isBlank(request.destinationCode())) {
            errors.add("destinationCode must not be blank");
        }

        List<RentalRateOverride> overrides = request.rentalRateOverrides();
        if (overrides != null && !overrides.isEmpty()) {
            if (isBlank(request.carClass())) {
                errors.add("carClass is required when rentalRateOverrides is provided");
            }
            for (RentalRateOverride override : overrides) {
                if (isBlank(override.companyCode())) {
                    errors.add("every rentalRateOverrides entry must have a non blank companyCode");
                }
                if (override.totalCents() <= 0) {
                    errors.add("every rentalRateOverrides entry must have totalCents greater than zero");
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new TripPlanValidationException(String.join("; ", errors));
        }
    }

    private static boolean isValidLatitude(double lat) {
        return lat >= -90.0 && lat <= 90.0;
    }

    private static boolean isValidLongitude(double lng) {
        return lng >= -180.0 && lng <= 180.0;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @ExceptionHandler(TripPlanValidationException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(TripPlanValidationException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }
}
