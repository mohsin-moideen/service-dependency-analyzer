package com.groupon.sda.api;

import com.groupon.sda.queue.QueueClosedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Translates exceptions thrown by controllers into the {@link ApiError} envelope.
 *
 * <p>The mapping table:
 * <pre>
 *   UnknownServiceException                -> 404  service_not_found
 *   IllegalArgumentException               -> 400  invalid_request
 *   HttpMessageNotReadableException        -> 400  invalid_request   (bad JSON)
 *   MethodArgumentTypeMismatchException    -> 400  invalid_request   (e.g. k=foo)
 *   MethodArgumentNotValidException        -> 400  invalid_request   (bean validation)
 *   QueueClosedException                   -> 503  service_unavailable (shutting down)
 *   Anything else                          -> 500  internal_error    (no stack trace in body)
 * </pre>
 *
 * <p>Spec quote: <i>"Returns structured errors (unknown service, malformed request, etc.)
 * — not stack traces."</i>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(UnknownServiceException.class)
    public ResponseEntity<ApiError> handleUnknownService(UnknownServiceException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.ofService("service_not_found", ex.getMessage(), ex.serviceId()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of("invalid_request", ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of("invalid_request", "malformed request body"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of("invalid_request",
                        "parameter '" + ex.getName() + "' has the wrong type"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of("invalid_request", ex.getMessage()));
    }

    @ExceptionHandler(QueueClosedException.class)
    public ResponseEntity<ApiError> handleQueueClosed(QueueClosedException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiError.of("service_unavailable", "ingest pipeline is shutting down"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleAnything(Exception ex) {
        // Log the full trace; return a generic body. Stack traces never go on the wire.
        log.error("unhandled exception in API", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("internal_error", "an unexpected error occurred"));
    }
}
