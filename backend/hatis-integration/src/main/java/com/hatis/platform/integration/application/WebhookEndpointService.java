package com.hatis.platform.integration.application;

import com.hatis.platform.authorization.application.AuthorizationService;
import com.hatis.platform.authorization.domain.ScopeType;
import com.hatis.platform.identity.application.TenantKeyService;
import com.hatis.platform.integration.adapter.persistence.IntegrationRepositories;
import com.hatis.platform.integration.adapter.persistence.WebhookEndpoint;
import com.hatis.platform.integration.domain.WebhookUrlValidator;
import com.hatis.platform.shared.audit.AuditRecord;
import com.hatis.platform.shared.audit.AuditRecorder;
import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.secret.EncryptionService;
import com.hatis.platform.shared.tenant.TenantContextHolder;
import com.hatis.platform.shared.tenant.TenantTransactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manages the URLs the platform posts events to, and the secrets that sign those posts.
 *
 * <h2>How the secret is protected</h2>
 *
 * Unlike an inbound integration, whose secret lives in the secret manager and is
 * referenced by path, an endpoint's secret is stored in this database as
 * {@code secret_ciphertext} plus the {@code dek_id} of the tenant key that encrypted it.
 * That is the envelope scheme the rest of the platform uses: a per-tenant data key, itself
 * wrapped by the platform key, resolved by {@link TenantKeyService} from
 * {@code org_organizations}. The plaintext exists only inside this method and in the
 * response that returns it once.
 *
 * <p>The consequence of storing the ciphertext here rather than in Vault is worth stating:
 * a database dump alone does not yield working signing secrets, but a database dump
 * <em>combined with</em> the platform key does. Revoking a tenant's key is what
 * cryptographically erases their endpoints.
 *
 * <p>One limitation follows from keying off the tenant's current key: rotating the
 * organization's data key invalidates ciphertext written under the previous one, so a
 * rotation has to re-encrypt these rows. {@code dek_id} records which key was used so that
 * such a migration can find them. This is the same constraint the identity context already
 * has for MFA secrets, not a new one.
 *
 * <h2>Validation happens here, not in the transport</h2>
 *
 * The URL is passed through {@link WebhookUrlValidator#validate} on the way in, which
 * refuses non-HTTP schemes, credentials in the URL, cluster-local hostnames and address
 * literals that are not publicly routable — deliberately without resolving DNS, because
 * this runs inside a request. Re-resolution happens per delivery, where a hostile resolver
 * cannot hold a user's request open.
 */
@Service
public class WebhookEndpointService {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 32 bytes, matching the platform's API keys and its inbound webhook secrets. */
    private static final int SECRET_BYTES = 32;

    private final IntegrationRepositories.WebhookEndpointRepository endpoints;
    private final TenantKeyService tenantKeys;
    private final EncryptionService encryption;
    private final AuthorizationService authorization;
    private final AuditRecorder audit;

    public WebhookEndpointService(IntegrationRepositories.WebhookEndpointRepository endpoints,
                                  TenantKeyService tenantKeys,
                                  EncryptionService encryption,
                                  AuthorizationService authorization,
                                  AuditRecorder audit) {
        this.endpoints = endpoints;
        this.tenantKeys = tenantKeys;
        this.encryption = encryption;
        this.authorization = authorization;
        this.audit = audit;
    }

    /**
     * Registers an endpoint and returns its signing secret exactly once.
     *
     * @return the view plus the plaintext secret; no later call can produce it again
     */
    @TenantTransactional
    public CreatedEndpoint register(@Valid RegisterCommand command) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        authorization.require("integration:write", ScopeType.ORGANIZATION, organizationId);

        URI uri = WebhookUrlValidator.validate(command.url());
        String plaintext = newSecret();
        TenantKeyService.TenantKey key = tenantKeys.keyForOrganization(organizationId);
        String ciphertext = encryption.encryptWith(key.wrappedDek(), key.keyId(), plaintext);

        WebhookEndpoint saved = endpoints.save(WebhookEndpoint.register(
                organizationId, uri.toString(), command.description(), command.events(),
                ciphertext, key.keyId()));

        audit.record(AuditRecord.builder("webhook.endpoint.registered")
                .resource("webhook_endpoint", saved.getId())
                .metadata(Map.of("url", saved.getUrl(), "events", saved.getEvents().size()))
                .build());
        return new CreatedEndpoint(EndpointView.from(saved), plaintext);
    }

    @TenantTransactional
    public List<EndpointView> list() {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        authorization.require("integration:read", ScopeType.ORGANIZATION, organizationId);
        return endpoints.findByOrganizationId(organizationId).stream()
                .map(EndpointView::from)
                .toList();
    }

    @TenantTransactional
    public EndpointView get(UUID endpointId) {
        return EndpointView.from(require(endpointId, "integration:read"));
    }

    @TenantTransactional
    public EndpointView update(UUID endpointId, @Valid UpdateCommand command) {
        WebhookEndpoint endpoint = require(endpointId, "integration:write");
        URI uri = WebhookUrlValidator.validate(command.url());
        endpoint.changeSubscription(uri.toString(), command.description(), command.events());
        audit.record(AuditRecord.builder("webhook.endpoint.updated")
                .resource("webhook_endpoint", endpoint.getId())
                .metadata(Map.of("url", endpoint.getUrl()))
                .build());
        return EndpointView.from(endpoints.save(endpoint));
    }

    /**
     * Stops deliveries without discarding the subscription.
     *
     * <p>Pausing is what a customer uses while their endpoint is down. Deleting would lose
     * the secret and force a re-registration on both sides.
     */
    @TenantTransactional
    public EndpointView pause(UUID endpointId) {
        return setPaused(endpointId, true, "webhook.endpoint.paused");
    }

    @TenantTransactional
    public EndpointView resume(UUID endpointId) {
        return setPaused(endpointId, false, "webhook.endpoint.resumed");
    }

    /**
     * Replaces the signing secret and returns the new plaintext exactly once.
     *
     * <p>There is no window in which both secrets are honoured. A dual-accept window is
     * the thing rotation is supposed to end, so the old secret stops verifying the moment
     * this commits — which is why the caller is told to configure the new one first.
     */
    @TenantTransactional
    public CreatedEndpoint rotateSecret(UUID endpointId) {
        WebhookEndpoint endpoint = require(endpointId, "integration:write");
        String plaintext = newSecret();
        TenantKeyService.TenantKey key =
                tenantKeys.keyForOrganization(endpoint.getOrganizationId());
        endpoint.rotateSecret(
                encryption.encryptWith(key.wrappedDek(), key.keyId(), plaintext), key.keyId());
        endpoints.save(endpoint);

        audit.record(AuditRecord.builder("webhook.endpoint.secret_rotated")
                .resource("webhook_endpoint", endpoint.getId())
                .build());
        return new CreatedEndpoint(EndpointView.from(endpoint), plaintext);
    }

    @TenantTransactional
    public void remove(UUID endpointId) {
        WebhookEndpoint endpoint = require(endpointId, "integration:write");
        endpoints.delete(endpoint);
        audit.record(AuditRecord.builder("webhook.endpoint.deleted")
                .resource("webhook_endpoint", endpointId)
                .build());
    }

    private EndpointView setPaused(UUID endpointId, boolean paused, String action) {
        WebhookEndpoint endpoint = require(endpointId, "integration:write");
        if (paused) {
            endpoint.pause();
        } else {
            endpoint.resume();
        }
        audit.record(AuditRecord.builder(action)
                .resource("webhook_endpoint", endpoint.getId())
                .build());
        return EndpointView.from(endpoints.save(endpoint));
    }

    /**
     * Loads an endpoint after checking the caller may act on it.
     *
     * <p>The explicit {@code organizationId} predicate is the control; row level security
     * is the backstop that still holds if someone adds a finder here without it.
     */
    private WebhookEndpoint require(UUID endpointId, String permission) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        authorization.require(permission, ScopeType.ORGANIZATION, organizationId);
        return endpoints.findByIdAndOrganizationId(endpointId, organizationId)
                .orElseThrow(() -> new PlatformExceptions.NotFound("Webhook endpoint", endpointId));
    }

    /**
     * A new signing secret.
     *
     * <p>Same shape as the inbound webhook secret, so operators recognise it and one
     * rotation procedure covers both.
     */
    static String newSecret() {
        byte[] random = new byte[SECRET_BYTES];
        RANDOM.nextBytes(random);
        return IntegrationService.SECRET_PREFIX
                + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    public record RegisterCommand(
            @NotBlank @Size(max = 512) String url,
            @Size(max = 512) String description,
            List<String> events) {
    }

    public record UpdateCommand(
            @NotBlank @Size(max = 512) String url,
            @Size(max = 512) String description,
            List<String> events) {
    }

    /**
     * Everything about an endpoint except its secret.
     *
     * <p>The ciphertext and the data key identifier are deliberately absent: this record
     * is serialized straight to the API, and neither belongs in a response or a log.
     */
    public record EndpointView(UUID id, String url, String description, List<String> events,
                               boolean active, Instant createdAt, Instant updatedAt) {

        public static EndpointView from(WebhookEndpoint endpoint) {
            return new EndpointView(endpoint.getId(), endpoint.getUrl(),
                    endpoint.getDescription(), endpoint.getEvents(), endpoint.isActive(),
                    endpoint.getCreatedAt(), endpoint.getUpdatedAt());
        }
    }

    /** Returned by {@code register} and {@code rotateSecret}, and by nothing else. */
    public record CreatedEndpoint(EndpointView endpoint, String secret) {
    }
}
