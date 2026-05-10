package com.groupon.sda.api;

/**
 * Stable JSON envelope returned by every error response. The spec calls for "structured
 * errors (unknown service, malformed request, etc.) — not stack traces"; this is the
 * shape every endpoint will produce on failure.
 *
 * @param error    short machine-readable code (e.g., {@code service_not_found})
 * @param message  human-readable description; safe to log or surface to a UI
 * @param service  the service id involved, when applicable; otherwise {@code null}
 */
public record ApiError(String error, String message, String service) {

    public static ApiError of(String error, String message) {
        return new ApiError(error, message, null);
    }

    public static ApiError ofService(String error, String message, String service) {
        return new ApiError(error, message, service);
    }
}
