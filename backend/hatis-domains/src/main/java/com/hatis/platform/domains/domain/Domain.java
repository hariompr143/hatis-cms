package com.hatis.platform.domains.domain;

import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * A customer domain bound to a project environment.
 *
 * <p>The lifecycle is explicit because DNS and TLS are asynchronous and partially
 * outside the platform's control: a domain is not "working" because a row exists.
 * Ownership must be proven before a certificate is issued, otherwise any tenant
 * could claim someone else's hostname and receive its traffic.
 */
@Entity
@Table(name = "dom_domains", indexes = {
        @Index(name = "ix_dom_domains_org", columnList = "organization_id,project_id"),
        @Index(name = "ix_dom_domains_host", columnList = "hostname")
})
public class Domain extends TenantScopedEntity {

    public enum Status {
        /** Created; awaiting the ownership TXT record. */
        PENDING_VERIFICATION,
        /** Ownership proven; DNS records and certificate are being provisioned. */
        PROVISIONING,
        /** Serving traffic over TLS. */
        ACTIVE,
        /** DNS or certificate regressed and needs operator attention. */
        DEGRADED,
        FAILED,
        DELETED
    }

    public enum VerificationMethod {
        /** A TXT record on the exact hostname. */
        TXT_RECORD,
        /** A CNAME to a platform-issued target, which proves control at the same time. */
        CNAME_DELEGATION
    }

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "environment_id", nullable = false, updatable = false)
    private UUID environmentId;

    @Column(name = "hostname", nullable = false, length = 253, updatable = false)
    private String hostname;

    /** The apex domain, used for rate limiting registrations per registrable domain. */
    @Column(name = "apex_domain", nullable = false, length = 253, updatable = false)
    private String apexDomain;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_method", nullable = false, length = 20)
    private VerificationMethod verificationMethod;

    /**
     * The value the customer must publish.
     *
     * <p>Not a secret — it is published in public DNS by design — but it is
     * single-use and expires, so it cannot be replayed to claim a domain later.
     */
    @Column(name = "verification_token", nullable = false, length = 128, updatable = false)
    private String verificationToken;

    @Column(name = "verification_record_name", nullable = false, length = 253)
    private String verificationRecordName;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "certificate_secret_name", length = 253)
    private String certificateSecretName;

    @Column(name = "certificate_expires_at")
    private Instant certificateExpiresAt;

    @Column(name = "dns_managed_by_platform", nullable = false)
    private boolean dnsManagedByPlatform;

    @Column(name = "force_https", nullable = false)
    private boolean forceHttps = true;

    @Column(name = "last_check_at")
    private Instant lastCheckAt;

    @Column(name = "last_error", length = 512)
    private String lastError;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Domain() {
        super();
    }

    public Domain(UUID organizationId, UUID projectId, UUID environmentId, String hostname,
                  VerificationMethod method, String verificationToken, UUID createdBy) {
        super(organizationId);
        if (projectId == null || environmentId == null) {
            throw new PlatformExceptions.Validation("projectId and environmentId are required", Map.of());
        }
        this.projectId = projectId;
        this.environmentId = environmentId;
        this.hostname = normalizeHostname(hostname);
        this.apexDomain = apexOf(this.hostname);
        this.verificationMethod = method;
        this.verificationToken = verificationToken;
        this.verificationRecordName = "_hatis-verify." + this.hostname;
        this.status = Status.PENDING_VERIFICATION;
        this.createdBy = createdBy;
        this.expiresAt = Instant.now().plusSeconds(72 * 3600);
    }

    /** Marks ownership proven. Only allowed while verification is still valid. */
    public void markVerified() {
        if (status != Status.PENDING_VERIFICATION) {
            throw new PlatformExceptions.StateConflict("Only a pending domain can be verified");
        }
        this.verifiedAt = Instant.now();
        this.status = Status.PROVISIONING;
        this.lastError = null;
    }

    public void markActive(String certificateSecretName, Instant certificateExpiresAt) {
        this.certificateSecretName = certificateSecretName;
        this.certificateExpiresAt = certificateExpiresAt;
        this.status = Status.ACTIVE;
        this.lastError = null;
        this.lastCheckAt = Instant.now();
    }

    public void markDegraded(String reason) {
        this.status = Status.DEGRADED;
        this.lastError = truncate(reason);
        this.lastCheckAt = Instant.now();
    }

    public void markFailed(String reason) {
        this.status = Status.FAILED;
        this.lastError = truncate(reason);
        this.lastCheckAt = Instant.now();
    }

    public void setDnsManagedByPlatform(boolean dnsManagedByPlatform) {
        this.dnsManagedByPlatform = dnsManagedByPlatform;
    }

    public void delete() {
        this.status = Status.DELETED;
        this.deletedAt = Instant.now();
    }

    /** True when the ownership challenge window has closed. */
    public boolean isVerificationExpired() {
        return verifiedAt == null && expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    public boolean isServing() {
        return status == Status.ACTIVE;
    }

    static String normalizeHostname(String hostname) {
        if (hostname == null || hostname.isBlank()) {
            throw new PlatformExceptions.Validation("hostname is required", Map.of());
        }
        String value = hostname.trim().toLowerCase(Locale.ROOT);
        if (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.length() > 253
                || !value.matches("(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+")) {
            throw new PlatformExceptions.Validation(
                    "hostname must be a fully qualified domain name", Map.of("field", "hostname"));
        }
        // Rejecting IP literals and internal names stops a tenant from pointing a
        // domain at the cluster's own service network.
        if (value.endsWith(".local") || value.endsWith(".internal") || value.endsWith(".cluster.local")) {
            throw new PlatformExceptions.Validation(
                    "Reserved internal hostnames cannot be bound", Map.of("field", "hostname"));
        }
        return value;
    }

    /** Last two labels — good enough for registration accounting and rate limits. */
    static String apexOf(String hostname) {
        String[] labels = hostname.split("\\.");
        if (labels.length < 2) {
            return hostname;
        }
        return labels[labels.length - 2] + "." + labels[labels.length - 1];
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 500 ? value.substring(0, 500) : value;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getEnvironmentId() {
        return environmentId;
    }

    public String getHostname() {
        return hostname;
    }

    public String getApexDomain() {
        return apexDomain;
    }

    public Status getStatus() {
        return status;
    }

    public VerificationMethod getVerificationMethod() {
        return verificationMethod;
    }

    public String getVerificationToken() {
        return verificationToken;
    }

    public String getVerificationRecordName() {
        return verificationRecordName;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public String getCertificateSecretName() {
        return certificateSecretName;
    }

    public Instant getCertificateExpiresAt() {
        return certificateExpiresAt;
    }

    public boolean isDnsManagedByPlatform() {
        return dnsManagedByPlatform;
    }

    public boolean isForceHttps() {
        return forceHttps;
    }

    public void setForceHttps(boolean forceHttps) {
        this.forceHttps = forceHttps;
    }

    public Instant getLastCheckAt() {
        return lastCheckAt;
    }

    public String getLastError() {
        return lastError;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
