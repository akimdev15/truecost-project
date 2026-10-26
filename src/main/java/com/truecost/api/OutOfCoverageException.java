package com.truecost.api;

/**
 * Thrown when a trip's origin, destination, or routed path leaves the area where tolls can be
 * priced, mapped to HTTP 422 with code OUT_OF_COVERAGE. A partial total would be biased toward the
 * per crossing and personal tag strategies, so the request is rejected instead.
 */
public class OutOfCoverageException extends RuntimeException {

    public static final String CODE = "OUT_OF_COVERAGE";

    public OutOfCoverageException(String message) {
        super(message);
    }
}
