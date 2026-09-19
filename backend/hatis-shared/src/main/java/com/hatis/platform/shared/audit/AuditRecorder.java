package com.hatis.platform.shared.audit;

/**
 * Outbound port used by every context to write an audit record.
 *
 * <p>Implemented by the {@code audit} context. Recording must never break the
 * business operation it describes: the implementation buffers failures and
 * reports them through the {@code hatis_audit_write_failures_total} metric
 * instead of throwing.
 */
public interface AuditRecorder {

    void record(AuditRecord record);

    /** Records a successful action against a resource. */
    default void success(String action, String resourceType, java.util.UUID resourceId) {
        record(AuditRecord.builder(action).resource(resourceType, resourceId).result(AuditRecord.Result.SUCCESS).build());
    }

    /** Records a denied action. Denials are as important as successes for incident review. */
    default void denied(String action, String resourceType, java.util.UUID resourceId, String reason) {
        record(AuditRecord.builder(action).resource(resourceType, resourceId)
                .result(AuditRecord.Result.DENIED).reason(reason).build());
    }
}
