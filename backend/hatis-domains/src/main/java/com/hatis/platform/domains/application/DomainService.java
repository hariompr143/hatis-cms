package com.hatis.platform.domains.application;

import com.hatis.platform.authorization.application.AuthorizationService;
import com.hatis.platform.authorization.domain.ScopeType;
import com.hatis.platform.domains.adapter.persistence.DomainRepository;
import com.hatis.platform.domains.domain.Domain;
import com.hatis.platform.domains.port.out.CertificateProvider;
import com.hatis.platform.domains.port.out.DnsProvider;
import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.event.EventPublisher;
import com.hatis.platform.shared.event.PlatformEvent;
import com.hatis.platform.shared.quota.QuotaKey;
import com.hatis.platform.shared.quota.QuotaService;
import com.hatis.platform.shared.tenant.TenantContextHolder;
import com.hatis.platform.shared.tenant.TenantTransactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Custom domain binding.
 *
 * <p>Order of operations is the security-relevant part:
 * <ol>
 *   <li>the hostname is registered and a single-use challenge is returned;</li>
 *   <li>the customer publishes the challenge in their DNS;</li>
 *   <li>the platform verifies it by live lookup, and only then</li>
 *   <li>a certificate is requested and traffic is routed.</li>
 * </ol>
 *
 * <p>Skipping straight to routing would let a tenant bind
 * {@code victim.example.com} and receive its traffic the moment DNS resolved to
 * the cluster.
 */
@Service
public class DomainService {

    private static final Logger log = LoggerFactory.getLogger(DomainService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DomainRepository domains;
    private final List<DnsProvider> dnsProviders;
    private final CertificateProvider certificates;
    private final AuthorizationService authorization;
    private final QuotaService quotas;
    private final EventPublisher events;
    private final String ingressCnameTarget;

    public DomainService(DomainRepository domains,
                         List<DnsProvider> dnsProviders,
                         CertificateProvider certificates,
                         AuthorizationService authorization,
                         QuotaService quotas,
                         EventPublisher events,
                         org.springframework.core.env.Environment environment) {
        this.domains = domains;
        this.dnsProviders = dnsProviders;
        this.certificates = certificates;
        this.authorization = authorization;
        this.quotas = quotas;
        this.events = events;
        this.ingressCnameTarget = environment.getProperty("hatis.domains.ingress-cname",
                "sites.hatis.example");
    }

    /** Registers a domain and returns the challenge the customer must publish. */
    @TenantTransactional
    public DomainRegistration register(UUID projectId, UUID environmentId, String hostname) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        authorization.require("domain:write", ScopeType.PROJECT, projectId);
        quotas.check(organizationId, QuotaKey.DOMAINS, 1);

        String normalized = Domain.normalizeHostname(hostname);
        if (domains.existsByOrganizationIdAndHostname(organizationId, normalized)) {
            throw new PlatformExceptions.AlreadyExists("The domain '" + normalized + "' is already bound");
        }
        // A hostname may only be bound by one tenant across the whole platform,
        // otherwise two customers could both claim the same site.
        if (domains.existsByHostname(normalized)) {
            throw new PlatformExceptions.StateConflict(
                    "The domain '" + normalized + "' is already in use by another workspace");
        }

        String token = challengeToken();
        Domain.VerificationMethod method = managedProvider(Domain.apexOf(normalized))
                .map(provider -> Domain.VerificationMethod.CNAME_DELEGATION)
                .orElse(Domain.VerificationMethod.TXT_RECORD);

        Domain domain = domains.save(new Domain(organizationId, projectId, environmentId, normalized,
                method, token, TenantContextHolder.require().principalId()));
        quotas.record(organizationId, QuotaKey.DOMAINS, 1);

        events.publish(PlatformEvent.of("domain.registered", organizationId)
                .resource("domain", domain.getId())
                .data(Map.of("hostname", normalized))
                .build());

        return new DomainRegistration(domain.getId(), domain.getHostname(), domain.getStatus().name(),
                domain.getVerificationRecordName(), method.name(),
                method == Domain.VerificationMethod.CNAME_DELEGATION ? ingressCnameTarget : token,
                domain.getExpiresAt());
    }

    /** Verifies ownership by live DNS lookup, then starts provisioning. */
    @TenantTransactional
    public Domain check(UUID domainId) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        authorization.require("domain:write", ScopeType.ORGANIZATION, organizationId);
        Domain domain = require(domainId, organizationId);

        if (domain.isVerificationExpired()) {
            throw new PlatformExceptions.StateConflict(
                    "The verification window has closed; register the domain again for a new challenge");
        }

        boolean verified = switch (domain.getVerificationMethod()) {
            case TXT_RECORD -> dnsProviders.stream()
                    .anyMatch(provider -> provider.recordMatches(domain.getVerificationRecordName(),
                            DnsProvider.RecordType.TXT, domain.getVerificationToken()));
            case CNAME_DELEGATION -> dnsProviders.stream()
                    .anyMatch(provider -> provider.recordMatches(domain.getHostname(),
                            DnsProvider.RecordType.CNAME, ingressCnameTarget));
        };

        if (!verified) {
            domain.markFailed("The expected DNS record is not published yet");
            domains.save(domain);
            return domain;
        }

        domain.markVerified();
        domains.save(domain);
        provision(domain);
        return domain;
    }

    /** Requests the certificate and records DNS records for platform-managed zones. */
    @TenantTransactional
    public Domain provision(Domain domain) {
        try {
            managedProvider(domain.getApexDomain()).ifPresent(provider -> {
                provider.upsertRecord(domain.getApexDomain(), domain.getHostname(),
                        DnsProvider.RecordType.CNAME, ingressCnameTarget, 300);
                domain.setDnsManagedByPlatform(true);
            });

            String secretName = "tls-" + domain.getHostname().replace('.', '-');
            CertificateProvider.IssuedCertificate issued = certificates.issue(domain.getHostname(),
                    List.of(),
                    new CertificateProvider.CertificateRequest("ClusterIssuer", "letsencrypt-prod",
                            secretName, domain.getApexDomain()));

            certificates.status(domain.getHostname())
                    .filter(CertificateProvider.CertificateStatus::ready)
                    .ifPresentOrElse(
                            status -> domain.markActive(issued.secretName(), status.notAfter()),
                            () -> domain.markDegraded("Certificate issued but not ready yet"));
        } catch (PlatformExceptions.DependencyUnavailable e) {
            domain.markDegraded("Certificate provider unavailable");
            log.warn("Certificate provisioning unavailable for {}: {}", domain.getHostname(), e.getMessage());
        }
        domains.save(domain);
        events.publish(PlatformEvent.of("domain.provisioned", domain.getOrganizationId())
                .resource("domain", domain.getId())
                .data(Map.of("hostname", domain.getHostname(), "status", domain.getStatus().name()))
                .build());
        return domain;
    }

    @TenantTransactional(readOnly = true)
    public List<DomainView> list(UUID projectId) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        authorization.require("domain:read", ScopeType.PROJECT, projectId);
        return domains.findByOrganizationIdAndProjectIdAndStatusNot(organizationId, projectId,
                        Domain.Status.DELETED)
                .stream().map(DomainView::from).toList();
    }

    @TenantTransactional(readOnly = true)
    public DomainView get(UUID domainId) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        authorization.require("domain:read", ScopeType.ORGANIZATION, organizationId);
        return DomainView.from(require(domainId, organizationId));
    }

    /** Re-checks DNS and certificate health; called by the reconciliation worker. */
    @TenantTransactional
    public Domain refresh(UUID domainId) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        Domain domain = require(domainId, organizationId);
        if (domain.getStatus() != Domain.Status.ACTIVE) {
            return check(domainId);
        }

        boolean dnsOk = dnsProviders.stream()
                .anyMatch(provider -> provider.recordMatches(domain.getHostname(),
                        DnsProvider.RecordType.CNAME, ingressCnameTarget));
        var status = certificates.status(domain.getHostname());
        boolean certOk = status.map(CertificateProvider.CertificateStatus::ready).orElse(false);

        if (!dnsOk) {
            domain.markDegraded("DNS no longer points at the platform");
        } else if (!certOk) {
            domain.markDegraded("Certificate is not ready");
        } else if (status.isPresent() && status.get().expiresWithin(Duration.ofDays(21))) {
            domain.markDegraded("Certificate expires within 21 days");
        } else {
            domain.markActive(domain.getCertificateSecretName(),
                    status.map(CertificateProvider.CertificateStatus::notAfter).orElse(null));
        }
        return domains.save(domain);
    }

    @TenantTransactional
    public void delete(UUID domainId) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        authorization.require("domain:write", ScopeType.ORGANIZATION, organizationId);
        Domain domain = require(domainId, organizationId);
        try {
            managedProvider(domain.getApexDomain()).ifPresent(provider ->
                    provider.deleteRecord(domain.getApexDomain(), domain.getHostname(),
                            DnsProvider.RecordType.CNAME));
            certificates.revoke(domain.getHostname());
        } catch (PlatformExceptions.DependencyUnavailable e) {
            // The domain row is still removed; a stale DNS record fails closed
            // because the ingress no longer serves the host.
            log.warn("Domain cleanup incomplete for {}: {}", domain.getHostname(), e.getMessage());
        }
        domain.delete();
        domains.save(domain);
        quotas.record(organizationId, QuotaKey.DOMAINS, -1);
        events.publish(PlatformEvent.of("domain.deleted", organizationId)
                .resource("domain", domainId)
                .build());
    }

    private Domain require(UUID domainId, UUID organizationId) {
        return domains.findByIdAndOrganizationId(domainId, organizationId)
                .filter(domain -> domain.getStatus() != Domain.Status.DELETED)
                .orElseThrow(() -> new PlatformExceptions.NotFound("Domain", domainId));
    }

    private java.util.Optional<DnsProvider> managedProvider(String apexDomain) {
        return dnsProviders.stream().filter(provider -> provider.manages(apexDomain)).findFirst();
    }

    /** A 32-character challenge. Publishable in public DNS, unusable elsewhere. */
    private static String challengeToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return "hatis-verify=" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record DomainRegistration(UUID id, String hostname, String status, String recordName,
                                     String verificationMethod, String recordValue,
                                     java.time.Instant expiresAt) {
    }

    /** Never includes the verification token: the registration response already had it. */
    public record DomainView(UUID id, UUID projectId, UUID environmentId, String hostname, String status,
                             String verificationMethod, boolean dnsManagedByPlatform,
                             java.time.Instant certificateExpiresAt, String lastError,
                             java.time.Instant lastCheckAt) {

        public static DomainView from(Domain domain) {
            return new DomainView(domain.getId(), domain.getProjectId(), domain.getEnvironmentId(),
                    domain.getHostname(), domain.getStatus().name(), domain.getVerificationMethod().name(),
                    domain.isDnsManagedByPlatform(), domain.getCertificateExpiresAt(),
                    domain.getLastError(), domain.getLastCheckAt());
        }
    }
}
