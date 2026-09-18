package com.hatis.platform.integration.application;

import com.hatis.platform.integration.adapter.persistence.IntegrationRepositories;
import com.hatis.platform.integration.domain.GitHubPushEvent;
import com.hatis.platform.integration.domain.GitHubSignatureVerifier;
import com.hatis.platform.integration.domain.InboundIntegration;
import com.hatis.platform.shared.audit.AuditRecord;
import com.hatis.platform.shared.audit.AuditRecorder;
import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.secret.Secret;
import com.hatis.platform.shared.secret.SecretStore;
import com.hatis.platform.shared.tenant.TenantContext;
import com.hatis.platform.shared.tenant.TenantContextHolder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Receives inbound webhooks from external systems, starting with GitHub.
 *
 * <p>This is the platform's external-CI/CD surface: a customer's pipeline pushes to
 * GitHub, GitHub calls us, and the platform records what landed without the customer
 * having to adopt our build system.
 *
 * <h2>How the tenant is established</h2>
 *
 * The caller presents no session and no token, only an HMAC, so the organization
 * cannot come from the usual place. It also cannot simply be read from the
 * integration row: {@code int_integrations} is under forced row level security, and a
 * transaction with no tenant bound sees zero rows — the lookup that would identify
 * the tenant is itself blocked by the isolation layer.
 *
 * The organization therefore arrives as a path segment, and that is safe only because
 * of what happens next. The path value is used solely to scope one RLS-bound read; it
 * is never an authorization decision. The delivery is accepted only if it carries a
 * valid HMAC under the secret belonging to <em>that</em> integration, and the caller
 * cannot have that secret unless the tenant provisioned it. An attacker who names
 * another tenant gets a scoped read of a row they cannot authenticate against, and the
 * same rejection either way, so nothing is disclosed.
 *
 * <p>Ordering matters and is easy to break: the signature is verified before anything
 * from the body is parsed, and before any tenant-scoped write happens.
 */
@Service
public class InboundWebhookService {

    private static final Logger log = LoggerFactory.getLogger(InboundWebhookService.class);

    /** The only event type acted on in Phase 1; others are acknowledged and recorded. */
    static final String PUSH_EVENT = "push";

    private final IntegrationRepositories.IntegrationRepository integrations;
    private final SecretStore secrets;
    private final InboundWebhookProcessor processor;
    private final AuditRecorder audit;
    private final ObjectMapper objectMapper;

    public InboundWebhookService(IntegrationRepositories.IntegrationRepository integrations,
                                 SecretStore secrets,
                                 InboundWebhookProcessor processor,
                                 AuditRecorder audit,
                                 ObjectMapper objectMapper) {
        this.integrations = integrations;
        this.secrets = secrets;
        this.processor = processor;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    /**
     * Verifies and records one inbound delivery.
     *
     * @param organizationId  the tenant named in the path; a routing hint that is
     *                        verified against the integration, never trusted
     * @param integrationId   the integration the delivery targets
     * @param signatureHeader the raw {@code X-Hub-Signature-256} value
     * @param deliveryId      GitHub's delivery GUID, for the audit trail
     * @param eventType       the {@code X-GitHub-Event} value
     * @param rawBody         the exact request bytes, unmodified
     * @return what was done, for the response body
     */
    public Outcome receive(UUID organizationId, UUID integrationId, String signatureHeader,
                           String deliveryId, String eventType, byte[] rawBody) {
        if (rawBody == null) {
            throw new PlatformExceptions.MalformedRequest("A request body is required");
        }
        String delivery = deliveryId == null || deliveryId.isBlank() ? "unknown" : deliveryId;

        TenantContext probe = TenantContext.of(organizationId, null,
                TenantContext.PrincipalType.SERVICE_ACCOUNT);
        return TenantContextHolder.callAs(probe, () -> {
            InboundIntegration integration = integrations
                    .findByIdAndOrganizationId(integrationId, organizationId)
                    .orElseThrow(() -> new PlatformExceptions.NotFound("integration"));

            verifySignature(integration, signatureHeader, rawBody, delivery);

            if (!PUSH_EVENT.equals(eventType)) {
                // The header is caller controlled and may be absent; Map.of rejects null,
                // and "unknown" is also the honest value to audit.
                String type = eventType == null || eventType.isBlank() ? "unknown" : eventType;
                processor.acknowledge(integration, type, delivery);
                return new Outcome("acknowledged", type, null, delivery);
            }

            GitHubPushEvent push = GitHubPushEvent.parse(readPayload(rawBody));
            processor.acceptPush(integration, push, delivery);
            return new Outcome("recorded", eventType, push.after(), delivery);
        });
    }

    /**
     * Proves the caller holds this integration's signing secret.
     *
     * <p>Fails closed on every branch: an integration with no secret reference, a
     * secret store that is unavailable, and a wrong signature are all rejections. The
     * audit entry records the rejection without recording the signature, the secret or
     * the body.
     */
    private void verifySignature(InboundIntegration integration, String signatureHeader,
                                 byte[] rawBody, String delivery) {
        if (!integration.acceptsDeliveries()) {
            reject(integration, delivery, "integration is not connected");
        }
        boolean valid;
        try (Secret secret = secrets.get(integration.getCredentialRef())) {
            if (secret == null || secret.isEmpty()) {
                reject(integration, delivery, "no webhook secret is configured");
                return;
            }
            valid = GitHubSignatureVerifier.matches(signatureHeader, rawBody, secret.reveal());
        } catch (PlatformExceptions.DependencyUnavailable e) {
            // The secret store is down. Rejecting is correct even though the caller may
            // be legitimate: an unverifiable delivery must not be acted on.
            log.warn("Secret store unavailable while verifying webhook {}", delivery);
            reject(integration, delivery, "the signing secret could not be read");
            return;
        }
        if (!valid) {
            reject(integration, delivery, "signature verification failed");
        }
    }

    private void reject(InboundIntegration integration, String delivery, String reason) {
        audit.record(AuditRecord.builder("integration.webhook.rejected")
                .organization(integration.getOrganizationId())
                .actor(AuditRecord.ActorType.SERVICE_ACCOUNT, null, null)
                .resource("integration", integration.getId())
                .result(AuditRecord.Result.DENIED)
                .reason(reason)
                .metadata(Map.of("provider", "github", "deliveryId", delivery))
                .build());
        // One message for every rejection, so the response cannot be used to tell an
        // unknown integration from a bad signature.
        throw new PlatformExceptions.Unauthenticated("The webhook signature could not be verified");
    }

    private Map<String, Object> readPayload(byte[] rawBody) {
        try {
            return objectMapper.readValue(rawBody, new TypeReference<Map<String, Object>>() { });
        } catch (java.io.IOException e) {
            throw new PlatformExceptions.MalformedRequest("The webhook body is not valid JSON");
        }
    }

    /**
     * @param status  what the platform did with the delivery
     * @param event   the GitHub event type
     * @param commit  the head SHA, when the event carried one
     * @param deliveryId GitHub's delivery GUID, echoed for correlation
     */
    public record Outcome(String status, String event, String commit, String deliveryId) {
    }
}
