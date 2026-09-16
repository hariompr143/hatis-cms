package com.hatis.platform.shared.error;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * Concrete platform failures. They are grouped in one file on purpose: the set of
 * failures the platform can raise is part of its public contract and should be
 * reviewable at a glance.
 */
public final class PlatformExceptions {

    private PlatformExceptions() {
    }

    /** Request body or parameters failed bean validation. */
    public static final class Validation extends PlatformException {
        public Validation(String message, Map<String, Object> details) {
            super(ErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, message, null, details);
        }
    }

    /** The request could not be parsed at all. */
    public static final class MalformedRequest extends PlatformException {
        public MalformedRequest(String message) {
            super(ErrorCode.MALFORMED_REQUEST, HttpStatus.BAD_REQUEST, message);
        }
    }

    /** No usable credentials were presented. */
    public static final class Unauthenticated extends PlatformException {
        public Unauthenticated(String message) {
            super(ErrorCode.UNAUTHENTICATED, HttpStatus.UNAUTHORIZED, message);
        }
    }

    /** Credentials were presented and rejected. Message must not distinguish unknown user from wrong password. */
    public static final class InvalidCredentials extends PlatformException {
        public InvalidCredentials() {
            super(ErrorCode.INVALID_CREDENTIALS, HttpStatus.UNAUTHORIZED, "Email or password is incorrect");
        }
    }

    /** The caller is authenticated but not allowed to perform this action on this resource. */
    public static final class Forbidden extends PlatformException {
        public Forbidden(String message) {
            super(ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN, message);
        }
    }

    /**
     * The resource exists but belongs to another tenant. Reported as 404 to the
     * caller so that existence is not disclosed across tenants; the audit record
     * and metric record the true reason.
     */
    public static final class TenantMismatch extends PlatformException {
        public TenantMismatch(String resourceType) {
            super(ErrorCode.TENANT_MISMATCH, HttpStatus.FORBIDDEN,
                    "Cross-tenant access to " + resourceType + " was denied");
        }
    }

    /** A tenant-scoped resource was not found within the caller's tenant. */
    public static final class NotFound extends PlatformException {
        public NotFound(String resourceType, Object id) {
            super(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, resourceType + " " + id + " was not found");
        }

        public NotFound(String message) {
            super(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, message);
        }
    }

    /** A uniqueness constraint or business uniqueness rule was violated. */
    public static final class AlreadyExists extends PlatformException {
        public AlreadyExists(String message) {
            super(ErrorCode.ALREADY_EXISTS, HttpStatus.CONFLICT, message);
        }
    }

    /** The aggregate is not in a state that permits the requested transition. */
    public static final class StateConflict extends PlatformException {
        public StateConflict(String message) {
            super(ErrorCode.STATE_CONFLICT, HttpStatus.CONFLICT, message);
        }
    }

    /** A plan quota would be exceeded by this action. */
    public static final class QuotaExceeded extends PlatformException {
        public QuotaExceeded(String limitKey, Object limit, Object requested) {
            super(ErrorCode.QUOTA_EXCEEDED, HttpStatus.CONFLICT,
                    "Quota exceeded for " + limitKey,
                    null,
                    Map.of("limitKey", limitKey, "limit", String.valueOf(limit), "requested", String.valueOf(requested)));
        }
    }

    /** The current plan does not include this capability. */
    public static final class EntitlementMissing extends PlatformException {
        public EntitlementMissing(String entitlement) {
            super(ErrorCode.ENTITLEMENT_MISSING, HttpStatus.PAYMENT_REQUIRED,
                    "The current plan does not include " + entitlement,
                    null,
                    Map.of("entitlement", entitlement));
        }
    }

    /** The same idempotency key was reused with a different request body. */
    public static final class IdempotencyConflict extends PlatformException {
        public IdempotencyConflict() {
            super(ErrorCode.IDEMPOTENCY_CONFLICT, HttpStatus.CONFLICT,
                    "This Idempotency-Key was already used with a different request");
        }
    }

    /** A domain invariant was violated. */
    public static final class BusinessRuleViolation extends PlatformException {
        public BusinessRuleViolation(String message) {
            super(ErrorCode.BUSINESS_RULE_VIOLATION, HttpStatus.UNPROCESSABLE_ENTITY, message);
        }
    }

    /** A platform policy (egress, upload, image source, ...) rejected the request. */
    public static final class PolicyViolation extends PlatformException {
        public PolicyViolation(String message) {
            super(ErrorCode.POLICY_VIOLATION, HttpStatus.UNPROCESSABLE_ENTITY, message);
        }
    }

    /** An external dependency could not be reached or answered in time. */
    public static final class DependencyUnavailable extends PlatformException {
        public DependencyUnavailable(String dependency, Throwable cause) {
            super(ErrorCode.DEPENDENCY_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE,
                    dependency + " is currently unavailable", cause);
        }
    }

    /** An asynchronous operation failed; the operation record holds the detail. */
    public static final class OperationFailed extends PlatformException {
        public OperationFailed(String message) {
            super(ErrorCode.OPERATION_FAILED, HttpStatus.INTERNAL_SERVER_ERROR, message);
        }
    }
}
