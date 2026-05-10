package com.systemdesign.gateway.exception;

/**
 * Base exception for all API Gateway errors.
 *
 * Hierarchy:
 *   GatewayException (this)
 *     ├── RouteNotFoundException      — no route matches the request path
 *     ├── ServiceUnavailableException — target service is down or circuit-broken
 *     └── RateLimitExceededException  — caller exceeded their rate limit
 */
public class GatewayException extends RuntimeException {

    public GatewayException(String message) {
        super(message);
    }

    public GatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
