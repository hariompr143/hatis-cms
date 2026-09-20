package com.hatis.platform.shared.operation;

import com.hatis.platform.shared.error.PlatformExceptions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.UUID;

/**
 * Lifecycle management for long-running operations.
 *
 * <p>Callers create the operation inside their own request transaction, hand the
 * id to the executor and return {@code 202 Accepted}. The executor owns every
 * subsequent state transition, so the API never lies about progress.
 */
@Service
public class OperationService {

    /** How long a worker may hold an operation before the reaper reclaims it. */
    public static final Duration DEFAULT_LEASE = Duration.ofMinutes(10);

    private final OperationRepository operations;

    public OperationService(OperationRepository operations) {
        this.operations = operations;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Operation start(UUID organizationId, String kind, String resourceType, UUID resourceId) {
        Operation operation = new Operation(organizationId, kind, resourceType, resourceId);
        return operations.save(operation);
    }

    @Transactional
    public Operation markRunning(UUID operationId, int progress) {
        Operation operation = load(operationId);
        operation.markRunning(progress, DEFAULT_LEASE);
        return operations.save(operation);
    }

    @Transactional
    public void updateProgress(UUID operationId, int progress) {
        Operation operation = load(operationId);
        operation.updateProgress(progress);
        operations.save(operation);
    }

    @Transactional
    public void markSucceeded(UUID operationId) {
        Operation operation = load(operationId);
        operation.markSucceeded();
        operations.save(operation);
    }

    @Transactional
    public void markFailed(UUID operationId, String errorCode, String message) {
        Operation operation = load(operationId);
        operation.markFailed(errorCode, message);
        operations.save(operation);
    }

    /** Scoped read: a tenant can only observe its own operations. */
    @Transactional(readOnly = true)
    public Operation get(UUID organizationId, UUID operationId) {
        return operations.findByIdAndOrganizationId(operationId, organizationId)
                .orElseThrow(() -> new PlatformExceptions.NotFound("Operation", operationId));
    }

    private Operation load(UUID operationId) {
        return operations.findById(operationId)
                .orElseThrow(() -> new PlatformExceptions.NotFound("Operation", operationId));
    }
}
