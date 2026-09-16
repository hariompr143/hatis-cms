package com.hatis.platform.shared.api;

import com.hatis.platform.shared.error.PlatformException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.ArrayList;
import java.util.List;

/**
 * One error contract for the whole platform.
 *
 * <p>Two rules matter more than the rest:
 * <ol>
 *   <li>A deliberately raised {@link PlatformException} keeps its message — it was
 *       written for a customer to read.</li>
 *   <li>Anything else is mapped to {@code internal_error} with a generic message.
 *       Stack traces, SQL and dependency errors are logged, never returned.</li>
 * </ol>
 *
 * <p>Every response carries the {@code correlationId} so a support request can be
 * traced end to end.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(PlatformException.class)
    public ResponseEntity<ApiError> handlePlatform(PlatformException ex, HttpServletRequest request) {
        if (ex.status().is5xxServerError()) {
            log.error("Platform failure on {} {}", request.getMethod(), request.getRequestURI(), ex);
        } else {
            log.info("Rejected {} {}: {} ({})", request.getMethod(), request.getRequestURI(),
                    ex.code().wireValue(), ex.getMessage());
        }
        return ResponseEntity.status(ex.status())
                .body(ApiError.of(ex.code().wireValue(), ex.getMessage(), correlationId(), ex.details()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleBeanValidation(MethodArgumentNotValidException ex) {
        List<ApiError.FieldError> errors = new ArrayList<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(fe -> errors.add(new ApiError.FieldError(
                        fe.getField(),
                        fe.getDefaultMessage() == null ? "is invalid" : fe.getDefaultMessage(),
                        safeValue(fe.getRejectedValue()))));
        ex.getBindingResult().getGlobalErrors()
                .forEach(ge -> errors.add(new ApiError.FieldError(
                        ge.getObjectName(), ge.getDefaultMessage(), null)));
        return ResponseEntity.badRequest().body(ApiError.of(
                "validation_failed", "Request validation failed", correlationId(), ApiError.fieldErrors(errors)));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex) {
        List<ApiError.FieldError> errors = new ArrayList<>();
        ex.getConstraintViolations().forEach(v -> errors.add(new ApiError.FieldError(
                v.getPropertyPath().toString(), v.getMessage(), null)));
        return ResponseEntity.badRequest().body(ApiError.of(
                "validation_failed", "Request validation failed", correlationId(), ApiError.fieldErrors(errors)));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleMalformed(Exception ex) {
        return ResponseEntity.badRequest()
                .body(ApiError.of("malformed_request", "The request could not be parsed", correlationId()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleMediaType(HttpMediaTypeNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ApiError.of("unsupported_media_type", "Unsupported content type", correlationId()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethod(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiError.of("malformed_request", "Method not allowed", correlationId()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleUploadSize(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiError.of("payload_too_large", "The uploaded file exceeds the maximum allowed size",
                        correlationId()));
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NoHandlerFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("not_found", "The requested resource does not exist", correlationId()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex) {
        // Raised by Spring Security's method-level checks. The reason is logged,
        // never returned: it would disclose which permission the caller lacks.
        log.info("Access denied: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of("forbidden", "You do not have access to this resource", correlationId()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("internal_error", "An unexpected error occurred", correlationId()));
    }

    /** Maps a rejected value for echoing back; secret-shaped values are never echoed. */
    private static Object safeValue(Object rejected) {
        if (rejected == null) {
            return null;
        }
        String text = String.valueOf(rejected);
        return text.length() > 120 ? text.substring(0, 117) + "..." : text;
    }

    /** MDC key set by {@code CorrelationIdFilter} for the duration of the request. */
    public static final String CORRELATION_ID_KEY = "correlationId";

    private static String correlationId() {
        return MDC.get(CORRELATION_ID_KEY);
    }
}
