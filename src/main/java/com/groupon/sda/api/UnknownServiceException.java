package com.groupon.sda.api;

/**
 * Thrown by a controller when the requested service id isn't in the graph. Mapped to
 * HTTP 404 with a structured error body by {@link GlobalExceptionHandler}.
 */
public class UnknownServiceException extends RuntimeException {

    private final String serviceId;

    public UnknownServiceException(String serviceId) {
        super("unknown service: " + serviceId);
        this.serviceId = serviceId;
    }

    public String serviceId() {
        return serviceId;
    }
}
