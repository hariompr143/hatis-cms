package com.hatis.platform.integration.application;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Inbound webhook handling.
 *
 * <p>The domain verifier has its own tests; these cover what only the service can get
 * wrong — the <em>order</em> of the checks, which tenant ends up bound, and whether an
 * unusable secret degrades into accepting the delivery. Those are the failures that
 * would not show up in any single class's unit tests.
 */
@ExtendWith(MockitoExtension.class)
class InboundWebhookServiceTest {

    private static final String SECRET = "hwhsec_test_secret_value";
    private static final String SECRET_PATH = "hatis/integrations/org/int/webhook-secret";
    private static final String PUSH_BODY = """
            {"ref":"refs/heads/main","before":"%s","after":"%s",
             "repository":{"full_name":"acme/shopfront"},"pusher":{"name":"octocat"},
             "commits":[{},{}]}
            """.formatted("1".repeat(40), "a".repeat(40));

    private final UUID organizationId = UUID.randomUUID();
    private final UUID integrationId = UUID.randomUUID();

    @Mock
    private IntegrationRepositories.IntegrationRepository repository;
    @Mock
    private SecretStore secrets;
    @Mock
    private InboundWebhookProcessor processor;
    @Mock
    private AuditRecorder audit;

    private InboundWebhookService service;

    @BeforeEach
    void setUp() {
        service = new InboundWebhookService(repository, secrets, processor, audit, new ObjectMapper());
    }

    @AfterEach
    void clearTenant() {
        // The service binds a tenant to this thread; leaking it into another test would
        // let a later test pass for the wrong reason.
        TenantContextHolder.clear();
    }

    private InboundIntegration connectedIntegration() {
        InboundIntegration integration = new InboundIntegration(
                organizationId, InboundIntegration.Type.GITHUB, "github-main", "{}", SECRET_PATH);
        integration.connect();
        return integration;
    }

    private String signatureFor(String body) {
        return GitHubSignatureVerifier.signatureFor(body.getBytes(StandardCharsets.UTF_8), SECRET);
    }

    @Test
    @DisplayName("a correctly signed push is recorded and published")
    void acceptsAValidPush() {
        when(repository.findByIdAndOrganizationId(integrationId, organizationId))
                .thenReturn(Optional.of(connectedIntegration()));
        when(secrets.get(SECRET_PATH)).thenReturn(Secret.of(SECRET));

        InboundWebhookService.Outcome outcome = service.receive(organizationId, integrationId,
                signatureFor(PUSH_BODY), "delivery-1", "push", PUSH_BODY.getBytes(StandardCharsets.UTF_8));

        assertThat(outcome.status()).isEqualTo("recorded");
        assertThat(outcome.commit()).isEqualTo("a".repeat(40));

        ArgumentCaptor<GitHubPushEvent> push = ArgumentCaptor.forClass(GitHubPushEvent.class);
        verify(processor).acceptPush(any(InboundIntegration.class), push.capture(), org.mockito.ArgumentMatchers.eq("delivery-1"));
        assertThat(push.getValue().repository()).isEqualTo("acme/shopfront");
        assertThat(push.getValue().branch()).isEqualTo("main");
        assertThat(push.getValue().commitCount()).isEqualTo(2);
        verify(audit, never()).record(any());
    }

    @Test
    @DisplayName("a bad signature is rejected before the body is parsed")
    void verifiesBeforeParsing() {
        when(repository.findByIdAndOrganizationId(integrationId, organizationId))
                .thenReturn(Optional.of(connectedIntegration()));
        when(secrets.get(SECRET_PATH)).thenReturn(Secret.of(SECRET));

        // A body that is not even JSON. If parsing happened first, this would surface as
        // MalformedRequest; the signature check must win, which is what this asserts.
        byte[] garbage = "not json at all".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> service.receive(organizationId, integrationId,
                "sha256=deadbeef", "delivery-2", "push", garbage))
                .isInstanceOf(PlatformExceptions.Unauthenticated.class);

        verify(processor, never()).acceptPush(any(), any(), anyString());
        verify(processor, never()).acknowledge(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("a valid signature on a malformed body is a malformed request")
    void parsesAfterVerifying() {
        when(repository.findByIdAndOrganizationId(integrationId, organizationId))
                .thenReturn(Optional.of(connectedIntegration()));
        when(secrets.get(SECRET_PATH)).thenReturn(Secret.of(SECRET));

        byte[] garbage = "not json at all".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> service.receive(organizationId, integrationId,
                signatureFor("not json at all"), "delivery-3", "push", garbage))
                .isInstanceOf(PlatformExceptions.MalformedRequest.class);
    }

    @Test
    @DisplayName("an integration that is not connected is rejected")
    void rejectsDisconnectedIntegration() {
        // PENDING: created but never connected, so it has no secret reference at all.
        InboundIntegration pending = new InboundIntegration(
                organizationId, InboundIntegration.Type.GITHUB, "github-main", "{}", null);
        when(repository.findByIdAndOrganizationId(integrationId, organizationId))
                .thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.receive(organizationId, integrationId,
                signatureFor(PUSH_BODY), "delivery-4", "push", PUSH_BODY.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(PlatformExceptions.Unauthenticated.class);

        // No secret is fetched for an integration that cannot accept deliveries.
        verify(secrets, never()).get(anyString());
        verify(processor, never()).acceptPush(any(), any(), anyString());
    }

    @Test
    @DisplayName("an unknown integration is not found, without leaking which check failed")
    void rejectsUnknownIntegration() {
        when(repository.findByIdAndOrganizationId(integrationId, organizationId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.receive(organizationId, integrationId,
                signatureFor(PUSH_BODY), "delivery-5", "push", PUSH_BODY.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(PlatformExceptions.NotFound.class);
    }

    @Test
    @DisplayName("an empty secret in the store fails closed")
    void failsClosedOnEmptySecret() {
        when(repository.findByIdAndOrganizationId(integrationId, organizationId))
                .thenReturn(Optional.of(connectedIntegration()));
        when(secrets.get(SECRET_PATH)).thenReturn(Secret.empty());

        assertThatThrownBy(() -> service.receive(organizationId, integrationId,
                signatureFor(PUSH_BODY), "delivery-6", "push", PUSH_BODY.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(PlatformExceptions.Unauthenticated.class);

        verify(processor, never()).acceptPush(any(), any(), anyString());
    }

    @Test
    @DisplayName("an unavailable secret store rejects rather than accepting the delivery")
    void failsClosedWhenSecretStoreIsDown() {
        when(repository.findByIdAndOrganizationId(integrationId, organizationId))
                .thenReturn(Optional.of(connectedIntegration()));
        when(secrets.get(SECRET_PATH))
                .thenThrow(new PlatformExceptions.DependencyUnavailable("secret store", "unreachable"));

        // A legitimate caller is refused. That is the correct trade: an unverifiable
        // delivery must never be acted on, and GitHub retries.
        assertThatThrownBy(() -> service.receive(organizationId, integrationId,
                signatureFor(PUSH_BODY), "delivery-7", "push", PUSH_BODY.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(PlatformExceptions.Unauthenticated.class);

        verify(processor, never()).acceptPush(any(), any(), anyString());
    }

    @Test
    @DisplayName("a rejected delivery is audited without the signature, secret or body")
    void auditsRejectionsSafely() {
        when(repository.findByIdAndOrganizationId(integrationId, organizationId))
                .thenReturn(Optional.of(connectedIntegration()));
        when(secrets.get(SECRET_PATH)).thenReturn(Secret.of(SECRET));

        assertThatThrownBy(() -> service.receive(organizationId, integrationId,
                "sha256=deadbeef", "delivery-8", "push", PUSH_BODY.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(PlatformExceptions.Unauthenticated.class);

        ArgumentCaptor<AuditRecord> record = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).record(record.capture());
        assertThat(record.getValue().action()).isEqualTo("integration.webhook.rejected");
        assertThat(record.getValue().result()).isEqualTo(AuditRecord.Result.DENIED);
        String metadata = String.valueOf(record.getValue().metadata());
        assertThat(metadata).doesNotContain(SECRET).doesNotContain("deadbeef");
    }

    @Test
    @DisplayName("the tenant is bound from the integration, and cleared afterwards")
    void bindsAndClearsTheTenant() {
        when(repository.findByIdAndOrganizationId(integrationId, organizationId))
                .thenReturn(Optional.of(connectedIntegration()));
        when(secrets.get(SECRET_PATH)).thenReturn(Secret.of(SECRET));

        AtomicReference<UUID> seen = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            TenantContext bound = TenantContextHolder.get();
            seen.set(bound == null ? null : bound.organizationId());
            return null;
        }).when(processor).acceptPush(any(), any(), anyString());

        service.receive(organizationId, integrationId, signatureFor(PUSH_BODY), "delivery-9", "push",
                PUSH_BODY.getBytes(StandardCharsets.UTF_8));

        assertThat(seen.get()).isEqualTo(organizationId);
        // Nothing was bound before the call, so nothing may remain after it.
        assertThat(TenantContextHolder.isPresent()).isFalse();
    }

    @Test
    @DisplayName("a non-push event is acknowledged rather than parsed as a push")
    void acknowledgesOtherEventTypes() {
        when(repository.findByIdAndOrganizationId(integrationId, organizationId))
                .thenReturn(Optional.of(connectedIntegration()));
        when(secrets.get(SECRET_PATH)).thenReturn(Secret.of(SECRET));

        String body = "{\"action\":\"opened\"}";
        InboundWebhookService.Outcome outcome = service.receive(organizationId, integrationId,
                signatureFor(body), "delivery-10", "pull_request", body.getBytes(StandardCharsets.UTF_8));

        assertThat(outcome.status()).isEqualTo("acknowledged");
        assertThat(outcome.event()).isEqualTo("pull_request");
        verify(processor).acknowledge(any(InboundIntegration.class),
                org.mockito.ArgumentMatchers.eq("pull_request"), anyString());
        verify(processor, never()).acceptPush(any(), any(), anyString());
    }

    @Test
    @DisplayName("an absent event type does not fail on a null map value")
    void toleratesAbsentEventType() {
        when(repository.findByIdAndOrganizationId(integrationId, organizationId))
                .thenReturn(Optional.of(connectedIntegration()));
        when(secrets.get(SECRET_PATH)).thenReturn(Secret.of(SECRET));

        String body = "{\"action\":\"opened\"}";
        InboundWebhookService.Outcome outcome = service.receive(organizationId, integrationId,
                signatureFor(body), "delivery-11", null, body.getBytes(StandardCharsets.UTF_8));

        assertThat(outcome.event()).isEqualTo("unknown");
        verify(processor).acknowledge(any(InboundIntegration.class),
                org.mockito.ArgumentMatchers.eq("unknown"), anyString());
    }
}
