package com.hatis.platform.identity.domain;

import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * A machine principal.
 *
 * <p>API keys belong to a service account rather than to a person, so that
 * automation keeps working when staff leave and revocation is a single, audited
 * action instead of a hunt through personal keys.
 */
@Entity
@Table(name = "idp_service_accounts")
public class ServiceAccount extends TenantScopedEntity {

    public enum Status {
        ACTIVE,
        DISABLED
    }

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", length = 2000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    protected ServiceAccount() {
        super();
    }

    public ServiceAccount(UUID organizationId, String name, String description) {
        super(organizationId);
        if (name == null || name.isBlank() || name.length() > 120) {
            throw new PlatformExceptions.Validation("name must be 1-120 characters", java.util.Map.of());
        }
        this.name = name.trim();
        this.description = description;
        this.status = Status.ACTIVE;
    }

    public void disable() {
        this.status = Status.DISABLED;
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Status getStatus() {
        return status;
    }
}
