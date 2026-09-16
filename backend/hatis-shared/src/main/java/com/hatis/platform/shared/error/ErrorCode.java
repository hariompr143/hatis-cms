package com.hatis.platform.shared.error;

/**
 * Stable, machine-readable error codes returned in the {@code code} field of
 * every error response. Clients may switch on these; they must never switch on
 * the human-readable {@code message}.
 *
 * <p>Codes are namespaced by concern so a customer can tell a validation problem
 * from an authorization problem from an infrastructure failure without parsing
 * prose.
 */
public enum ErrorCode {

    // 400
    VALIDATION_FAILED("validation_failed"),
    MALFORMED_REQUEST("malformed_request"),
    UNSUPPORTED_MEDIA_TYPE("unsupported_media_type"),
    PAYLOAD_TOO_LARGE("payload_too_large"),

    // 401 / 403
    UNAUTHENTICATED("unauthenticated"),
    INVALID_CREDENTIALS("invalid_credentials"),
    MFA_REQUIRED("mfa_required"),
    ACCOUNT_LOCKED("account_locked"),
    TOKEN_EXPIRED("token_expired"),
    TOKEN_REVOKED("token_revoked"),
    FORBIDDEN("forbidden"),
    TENANT_MISMATCH("tenant_mismatch"),
    PRODUCTION_ACCESS_DENIED("production_access_denied"),

    // 404 / 409
    NOT_FOUND("not_found"),
    ALREADY_EXISTS("already_exists"),
    STATE_CONFLICT("state_conflict"),
    QUOTA_EXCEEDED("quota_exceeded"),
    ENTITLEMENT_MISSING("entitlement_missing"),
    IDEMPOTENCY_CONFLICT("idempotency_conflict"),

    // 422 / 429
    BUSINESS_RULE_VIOLATION("business_rule_violation"),
    POLICY_VIOLATION("policy_violation"),
    RATE_LIMITED("rate_limited"),

    // 5xx
    INTERNAL_ERROR("internal_error"),
    DEPENDENCY_UNAVAILABLE("dependency_unavailable"),
    OPERATION_FAILED("operation_failed");

    private final String wireValue;

    ErrorCode(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
