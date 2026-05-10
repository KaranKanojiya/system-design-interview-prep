package com.systemdesign.gateway.exception;

/**
 * Thrown when the target service is unavailable (circuit-breaker open, no healthy instances, etc.).
 *
 * Flow: Route matched → LoadBalancer finds no healthy instance → ServiceUnavailableException → 503
 */
public class ServiceUnavailableException extends GatewayException {

    private final String serviceName; // the service that could not be reached

    public ServiceUnavailableException(String serviceName) {
        super("Service unavailable: " + serviceName);
        this.serviceName = serviceName;
    }

    public String getServiceName() {
        return serviceName;
    }

    @Override
    public String getMessage() {
        return "Service unavailable: " + serviceName;
    }
}
