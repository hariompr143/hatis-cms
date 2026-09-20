package com.hatis.platform.integration.adapter.rest;

import com.hatis.platform.integration.application.InboundWebhookService;
import com.hatis.platform.integration.domain.GitHubSignatureVerifier;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Inbound webhook receiver for GitHub.
 *
 * <p>Unauthenticated in the JWT sense — the caller is GitHub — and therefore listed
 * in the security configuration's permit-all set. Authentication is the HMAC, which
 * {@link InboundWebhookService} verifies before anything else happens.
 *
 * <p>The organization appears in the path for a reason that is not obvious and is
 * explained on the service: the integrations table is under forced row level security,
 * so a transaction with no tenant bound cannot read the row that would identify the
 * tenant. The path value scopes one read and is then checked against the row; it never
 * authorizes anything.
 *
 * <p>Returns 202 rather than 200 because the platform records the delivery and acts on
 * it asynchronously. GitHub retries on non-2xx, so a failure here must mean "we did not
 * accept this", not "processing was slow".
 */
@RestController
@RequestMapping("/v1/integrations/github")
@Tag(name = "Integrations", description = "Inbound webhooks from external systems")
public class GitHubWebhookController {

    private final InboundWebhookService webhooks;

    public GitHubWebhookController(InboundWebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping("/{organizationId}/{integrationId}/events")
    @Operation(summary = "Receive a GitHub webhook delivery")
    public ResponseEntity<InboundWebhookService.Outcome> receive(
            @PathVariable UUID organizationId,
            @PathVariable UUID integrationId,
            // Not required=true by default: an absent header must reach the verifier so
            // that it rejects, rather than failing here with a less useful 400.
            @RequestHeader(value = GitHubSignatureVerifier.SIGNATURE_HEADER, required = false)
            String signature,
            @RequestHeader(value = GitHubSignatureVerifier.DELIVERY_HEADER, required = false)
            String deliveryId,
            @RequestHeader(value = GitHubSignatureVerifier.EVENT_HEADER, required = false)
            String eventType,
            // Bound as raw bytes on purpose. GitHub signs the exact bytes it sent, so
            // re-serialising a parsed DTO would produce a different digest and every
            // legitimate delivery would fail verification.
            @RequestBody(required = false) byte[] rawBody) {

        InboundWebhookService.Outcome outcome = webhooks.receive(
                organizationId, integrationId, signature, deliveryId, eventType,
                rawBody == null ? new byte[0] : rawBody);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(outcome);
    }
}
