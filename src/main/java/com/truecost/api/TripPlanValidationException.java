package com.truecost.api;

/** Thrown by TripController's validation, mapped to HTTP 400 by its exception handler. */
public class TripPlanValidationException extends RuntimeException {

    public TripPlanValidationException(String message) {
        super(message);
    }
}
