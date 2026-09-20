package com.hatis.platform.integration.application;

import com.hatis.platform.authorization.application.AuthorizationService;
import com.hatis.platform.authorization.domain.ScopeType;
import com.hatis.platform.integration.adapter.persistence.IntegrationRepositories;
import com.hatis.platform.integration.domain.InboundIntegration;
import com.hatis.platform.shared.audit.AuditRecord;
import com.hatis.platform.shared.audit.AuditRecorder;
import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.secret.Secret;
import com.hatis.platform.shared.secret.SecretStore;
import com.hatis.platform.shared.tenant.TenantContextHolder;
import com.hatis.platform.shared.tenant.TenantTransactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manages connections to external systems, and the signing secrets that authenticate
 * their inbound deliveries.
 *
 * <h2>Why the secret is stored retrievably rather than hashed</h2>
 *
 * API keys are stored as a SHA-256 hash because the platform only ever needs to
 * <em>compare</em> one. A webhook signing secret is different: verifying an HMAC
 * requires the actual key, so the platform must be able to read it back. That is why it
 * lives in the secret manager under {@link #secretPath} and only a path is persisted
 * here. The consequence is worth stating plainly — a compromise of the secret store
 * exposes working signing secrets, which is exactly the boundary the secret manager
 * exists to hold, and why the adapters are Vault or a cloud KMS rather than a file.
 *
 * <p>The plaintext is returned to the caller exactly once, on connect and on rotate.
 * It is never logged, never stored in this database and never returned again.
 */
@Service
public class IntegrationService {

    private static final Logger log = LoggerFactory.getLogger(IntegrationService.class);

    /** Displayed so operators recognise a HATIS-issued secret; carries no entropy. */
    static final String SECRET_PREFIX = "hwhsec_";

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 32 bytes of entropy, matching the platform's API keys. */
    private static final int SECRET_BYTES = 32;

    private final IntegrationRepositories.IntegrationRepository integrations;
    private final SecretStore secrets;
    private final AuthorizationService authorization;
    private final AuditRecorder audit;

    public IntegrationService(IntegrationRepositories.IntegrationRepository integrations,
                              SecretStore secrets,
                              AuthorizationService authorization,
                              AuditRecorder audit) {
        this.integrations = integrations;
        this.secrets = secrets;
        this.authorization = authorization;
        this.audit = audit;
    }

    /** Creates an integration in PENDING. It accepts nothing until it is connected. */
    @TenantTransactional
    public IntegrationView create(@Valid CreateIntegrationCommand command) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        authorization.require("integration:write", ScopeType.ORGANIZATION, organizationId);

        integrations.findByOrganizationIdAndName(organizationId, command.name()).ifPresent(existing -> {
            throw new PlatformExceptions.AlreadyExists("An integration named '" + command.name() + "' already exists");
        });

        InboundIntegration saved = integrations.save(new InboundIntegration(
                organizationId, command.type(), command.name(), command.config(), null));

        audit.record(AuditRecord.builder("integration.created")
                .resource("integration", saved.getId())
                .metadata(Map.of("name", saved.getName(), "type", saved.getType().name()))
                .build());
        return IntegrationView.from(saved, endpointPath(organizationId, saved.getId()));
    }

    /**
     * Generates a signing secret, stores it and activates the integration.
     *
     * @return the view plus the plaintext secret, which is not recoverable afterwards
     */
    @TenantTransactional
    public ConnectedIntegration connect(UUID integrationId) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        authorization.require("integration:write", ScopeType.ORGANIZATION, organizationId);

        InboundIntegration integration = require(organizationId, integrationId);
        if (integration.getStatus() == InboundIntegration.Status.CONNECTED) {
            throw new PlatformExceptions.StateConflict(
                    "That integration is already connected; rotate its secret instead");
        }

        String plaintext = issueSecret(organizationId, integration);
        integration.connect();
        integrations.save(integration);

        audit.record(AuditRecord.builder("integration.connected")
                .resource("integration", integrationId)
                .metadata(Map.of("type", integration.getType().name()))
                .build());
        return new ConnectedIntegration(
                IntegrationView.from(integration, endpointPath(organizationId, integrationId)), plaintext);
    }

    /**
     * Replaces the signing secret.
     *
     * <p>The old secret is deleted rather than left readable, so a leaked secret stops
     * working as soon as rotation completes. The caller must reconfigure the external
     * system with the new value; there is deliberately no grace window in which both
     * are accepted, because that window is what makes rotation ineffective.
     */
    @TenantTransactional
    public ConnectedIntegration rotate(UUID integrationId) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        authorization.require("integration:write", ScopeType.ORGANIZATION, organizationId);

        InboundIntegration integration = require(organizationId, integrationId);
        String plaintext = issueSecret(organizationId, integration);
        integrations.save(integration);

        audit.record(AuditRecord.builder("integration.secret_rotated")
                .resource("integration", integrationId)
                .build());
        return new ConnectedIntegration(
                IntegrationView.from(integration, endpointPath(organizationId, integrationId)), plaintext);
    }

    /** Stops deliveries and deletes the stored secret. */
    @TenantTransactional
    public void disconnect(UUID integrationId) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        authorization.require("integration:write", ScopeType.ORGANIZATION, organizationId);

        InboundIntegration integration = require(organizationId, integrationId);
        String path = integration.getCredentialRef();
        integration.disconnect();
        integrations.save(integration);

        if (path != null) {
            try {
                secrets.delete(path);
            } catch (RuntimeException e) {
                // The row is already disconnected, so deliveries stop regardless. The
                // orphaned secret is reported rather than swallowed: an operator has to
                // know a credential is still sitting in the store.
                log.error("Integration {} disconnected but its secret at {} could not be deleted",
                        integrationId, path, e);
                throw new PlatformExceptions.DependencyUnavailable("secret store",
                        "the integration is disconnected but its signing secret could not be deleted");
            }
        }

        audit.record(AuditRecord.builder("integration.disconnected")
                .resource("integration", integrationId)
                .build());
    }

    @TenantTransactional(readOnly = true)
    public List<IntegrationView> list() {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        authorization.require("integration:read", ScopeType.ORGANIZATION, organizationId);
        return integrations.findByOrganizationIdAndType(organizationId, InboundIntegration.Type.GITHUB)
                .stream()
                .map(i -> IntegrationView.from(i, endpointPath(organizationId, i.getId())))
                .toList();
    }

    private InboundIntegration require(UUID organizationId, UUID integrationId) {
        return integrations.findByIdAndOrganizationId(integrationId, organizationId)
                .orElseThrow(() -> new PlatformExceptions.NotFound("integration", integrationId));
    }

    /** Generates a secret, writes it to the store and binds its path to the integration. */
    private String issueSecret(UUID organizationId, InboundIntegration integration) {
        byte[] random = new byte[SECRET_BYTES];
        RANDOM.nextBytes(random);
        String plaintext = SECRET_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String path = secretPath(organizationId, integration.getId());
        secrets.put(path, Secret.of(plaintext));
        integration.bindCredential(path);
        return plaintext;
    }

    /** Secret-store path for an integration's signing secret. */
    static String secretPath(UUID organizationId, UUID integrationId) {
        return "hatis/integrations/" + organizationId + "/" + integrationId + "/webhook-secret";
    }

    /**
     * The path GitHub is configured to call.
     *
     * <p>Customers are given a path, not an absolute URL: the platform does not know its
     * own public hostname in a private deployment, and a wrong absolute URL is worse
     * than a relative one the operator completes.
     */
    static String endpointPath(UUID organizationId, UUID integrationId) {
        return "/v1/integrations/github/" + organizationId + "/" + integrationId + "/events";
    }

    public record CreateIntegrationCommand(
            @NotBlank @Size(max = 120) String name,
            @NotNull InboundIntegration.Type type,
            @Size(max = 4000) String config) {
    }

    /**
     * @param id             the integration
     * @param name           its display name
     * @param type           the external system
     * @param status         PENDING until connected
     * @param connected      whether it will accept deliveries
     * @param endpointPath   where the external system should POST
     * @param hasSecret      whether a signing secret exists; never the secret or its path
     */
    public record IntegrationView(UUID id, String name, InboundIntegration.Type type,
                                  InboundIntegration.Status status, boolean connected,
                                  String endpointPath, boolean hasSecret) {

        public static IntegrationView from(InboundIntegration integration, String endpointPath) {
            return new IntegrationView(integration.getId(), integration.getName(), integration.getType(),
                    integration.getStatus(), integration.acceptsDeliveries(), endpointPath,
                    integration.getCredentialRef() != null);
        }
    }

    /** Carries the plaintext secret, and is the only response that ever does. */
    public record ConnectedIntegration(IntegrationView integration, String secret) {
    }
}
