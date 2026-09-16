package com.hatis.platform.shared.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The single error shape returned by every HATIS API.
 *
 * <pre>
 * {
 *   "error": {
 *     "code": "validation_failed",
 *     "message": "Request validation failed",
 *     "correlationId": "01890f...",
 *     "timestamp": "2026-09-16T19:40:00Z",
 *     "details": { "fieldErrors": [ { "field": "email", "message": "must be a valid email" } ] }
 *   }
 * }
 * </pre>
 *
 * <p>Internal exception messages are never placed in {@code message} unless the
 * failure was raised deliberately as a {@code PlatformException}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        String code,
        String message,
        String correlationId,
        Instant timestamp,
        Map<String, Object> details) {

    public static ApiError of(String code, String message, String correlationId) {
        return new ApiError(code, message, correlationId, Instant.now(), null);
    }

    public static ApiError of(String code,
                              String message,
                              String correlationId,
                              Map<String, Object> details) {
        return new ApiError(code, message, correlationId, Instant.now(), details);
    }

    public record FieldError(String field, String message, Object rejectedValue) {
    }

    public static Map<String, Object> fieldErrors(List<FieldError> errors) {
        return Map.of("fieldErrors", errors);
    }
}
