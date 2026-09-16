package com.hatis.platform.shared.error;

import org.springframework.http.HttpStatus;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base type for every failure the platform raises deliberately.
 *
 * <p>Carrying the HTTP status and the stable {@link ErrorCode} on the exception
 * means controllers never translate failures, and the global handler never has to
 * guess. Unexpected exceptions are <em>not</em> subclasses of this type: they are
 * mapped to {@code internal_error} without leaking their message.
 */
public abstract class PlatformException extends RuntimeException {

    private final ErrorCode code;
    private final HttpStatus status;
    private final Map<String, Object> details;

    protected PlatformException(ErrorCode code, HttpStatus status, String message) {
        this(code, status, message, null, Collections.emptyMap());
    }

    protected PlatformException(ErrorCode code, HttpStatus status, String message, Throwable cause) {
        this(code, status, message, cause, Collections.emptyMap());
    }

    protected PlatformException(ErrorCode code,
                                HttpStatus status,
                                String message,
                                Throwable cause,
                                Map<String, Object> details) {
        super(message, cause);
        this.code = code;
        this.status = status;
        this.details = details == null ? Collections.emptyMap() : Map.copyOf(details);
    }

    public ErrorCode code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public Map<String, Object> details() {
        return details;
    }

    /** Convenience builder for the field-level detail map. */
    protected static Map<String, Object> detail(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }
}
