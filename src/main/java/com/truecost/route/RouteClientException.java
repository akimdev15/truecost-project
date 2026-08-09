package com.truecost.route;

/** Thrown when OSRM is unreachable, returns a non Ok status, or returns a response this client cannot parse. */
public class RouteClientException extends RuntimeException {

    public RouteClientException(String message) {
        super(message);
    }

    public RouteClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
