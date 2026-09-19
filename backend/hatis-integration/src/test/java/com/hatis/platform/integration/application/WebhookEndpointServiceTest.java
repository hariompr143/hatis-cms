package com.hatis.platform.integration.application;

import com.hatis.platform.authorization.application.AuthorizationService;
import com.hatis.platform.authorization.domain.ScopeType;
import com.hatis.platform.identity.application.TenantKeyService;
import com.hatis.platform.integration.adapter.persistence.IntegrationRepositories;
import com.hatis.platform.integration.adapter.persistence.WebhookEndpoint;
import com.hatis.platform.shared.audit.AuditRecord;
import com.hatis.platform.shared.audit.AuditRecorder;
import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.secret.EncryptionService;
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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Endpoint registration and secret handling.
 *
 * <p>These tests are mostly about one property: the plaintext signing secret exists only in
 * the response that hands it over. It must not land in the persisted entity, the audit
 * record or any view, because each of those outlives the request. Several of them exist
 * purely to fail if that changes.
 */
@ExtendWith(MockitoExtension.class)
class WebhookEndpointServiceTest {

    private static final String WRAPPED_DEK = "wrapped-dek-material";
    private static final String KEY_ID = "key-2026-01";
    private static final String CIPHERTEXT = "aes-gcm-ciphertext-not-a-secret";

    private final UUID organizationId = UUID.randomUUID();
    private final UUID principalId = UUID.randomUUID();

    @Mock
    private IntegrationRepositories.WebhookEndpointRepository repository;
    @Mock
    private TenantKeyService tenantKeys;
    @Mock
    private EncryptionService encryption;
    @Mock
    private AuthorizationService authorization;
    @Mock
    private AuditRecorder audit;

    private WebhookEndpointService service;

    @BeforeEach
    void setUp() {
        TenantContextHolder.set(TenantContext.of(organizationId, principalId,
                TenantContext.PrincipalType.USER));
        service = new WebhookEndpointService(repository, tenantKeys, encryption,
                authorization, audit);
    }

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("registering stores ciphertext under the tenant key and returns the plaintext once")
    void registerEncryptsAndReturnsThePlaintextOnce() {
        stubKeyAndEncryption();
        when(repository.save(any(WebhookEndpoint.class))).thenAnswer(i -> i.getArgument(0));

        WebhookEndpointService.CreatedEndpoint created = service.register(
                new WebhookEndpointService.RegisterCommand(
                        "https://hooks.acme.example/hatis", "Production",
                        List.of("content.published")));

        ArgumentCaptor<WebhookEndpoint> saved = ArgumentCaptor.forClass(WebhookEndpoint.class);
        verify(repository).save(saved.capture());

        assertThat(saved.getValue().getSecretCiphertext()).isEqualTo(CIPHERTEXT);
        assertThat(saved.getValue().getDekId()).isEqualTo(KEY_ID);
        // The plaintext is returned, and it is not what was stored.
        assertThat(created.secret()).startsWith(IntegrationService.SECRET_PREFIX);
        assertThat(created.secret()).isNotEqualTo(CIPHERTEXT);
        assertThat(saved.getValue().getSecretCiphertext()).doesNotContain(created.secret());
        assertThat(created.endpoint().url()).isEqualTo("https://hooks.acme.example/hatis");
        assertThat(created.endpoint().events()).containsExactly("content.published");
        assertThat(created.endpoint().active()).isTrue();
    }

    @Test
    @DisplayName("the plaintext is what gets encrypted, not something already stored")
    void thePlaintextIsTheEncryptionInput() {
        stubKeyAndEncryption();
        when(repository.save(any(WebhookEndpoint.class))).thenAnswer(i -> i.getArgument(0));

        WebhookEndpointService.CreatedEndpoint created = service.register(
                new WebhookEndpointService.RegisterCommand(
                        "https://hooks.acme.example/hatis", null, List.of()));

        ArgumentCaptor<String> plaintext = ArgumentCaptor.forClass(String.class);
        verify(encryption).encryptWith(eq(WRAPPED_DEK), eq(KEY_ID), plaintext.capture());
        assertThat(plaintext.getValue()).isEqualTo(created.secret());
    }

    @Test
    @DisplayName("the plaintext never appears in the audit record")
    void theAuditRecordCarriesNoSecret() {
        stubKeyAndEncryption();
        when(repository.save(any(WebhookEndpoint.class))).thenAnswer(i -> i.getArgument(0));

        WebhookEndpointService.CreatedEndpoint created = service.register(
                new WebhookEndpointService.RegisterCommand(
                        "https://hooks.acme.example/hatis", "Production", List.of()));

        ArgumentCaptor<AuditRecord> record = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).record(record.capture());
        // The record is retained long after the request, so a secret in it would outlive
        // every other copy of that secret.
        assertThat(record.getValue().toString()).doesNotContain(created.secret());
        assertThat(record.getValue().toString()).doesNotContain(CIPHERTEXT);
    }

    @Test
    @DisplayName("registering refuses a URL pointing at the cloud metadata endpoint")
    void registerRefusesAnInternalUrl() {
        assertThatThrownBy(() -> service.register(new WebhookEndpointService.RegisterCommand(
                "http://169.254.169.254/latest/meta-data/iam/", null, List.of())))
                .isInstanceOf(PlatformExceptions.Validation.class);

        verify(repository, never()).save(any());
        verify(encryption, never()).encryptWith(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("registering refuses a non-HTTP scheme")
    void registerRefusesANonHttpScheme() {
        assertThatThrownBy(() -> service.register(new WebhookEndpointService.RegisterCommand(
                "file:///etc/passwd", null, List.of())))
                .isInstanceOf(PlatformExceptions.Validation.class);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("registering checks the write permission against the caller's organization")
    void registerChecksTheWritePermission() {
        stubKeyAndEncryption();
        when(repository.save(any(WebhookEndpoint.class))).thenAnswer(i -> i.getArgument(0));

        service.register(new WebhookEndpointService.RegisterCommand(
                "https://hooks.acme.example/hatis", null, List.of()));

        verify(authorization).require("integration:write", ScopeType.ORGANIZATION, organizationId);
    }

    @Test
    @DisplayName("listing checks the read permission and exposes no secret material")
    void listExposesNoSecretMaterial() {
        WebhookEndpoint endpoint = newEndpoint("https://hooks.acme.example/hatis");

        when(repository.findByOrganizationId(organizationId)).thenReturn(List.of(endpoint));

        List<WebhookEndpointService.EndpointView> views = service.list();

        verify(authorization).require("integration:read", ScopeType.ORGANIZATION, organizationId);
        assertThat(views).hasSize(1);
        assertThat(views.get(0).toString()).doesNotContain(CIPHERTEXT);
        assertThat(views.get(0).toString()).doesNotContain(KEY_ID);
    }

    @Test
    @DisplayName("rotating replaces the ciphertext and returns a different plaintext")
    void rotateReplacesTheSecret() {
        WebhookEndpoint endpoint = newEndpoint("https://hooks.acme.example/hatis");
        when(repository.findByIdAndOrganizationId(endpoint.getId(), organizationId))
                .thenReturn(Optional.of(endpoint));
        when(tenantKeys.keyForOrganization(organizationId))
                .thenReturn(new TenantKeyService.TenantKey(WRAPPED_DEK, KEY_ID));
        when(encryption.encryptWith(anyString(), anyString(), anyString()))
                .thenReturn("rotated-ciphertext");
        when(repository.save(any(WebhookEndpoint.class))).thenAnswer(i -> i.getArgument(0));

        WebhookEndpointService.CreatedEndpoint rotated = service.rotateSecret(endpoint.getId());

        assertThat(endpoint.getSecretCiphertext()).isEqualTo("rotated-ciphertext");
        assertThat(rotated.secret()).isNotEqualTo(CIPHERTEXT);
        assertThat(rotated.secret()).startsWith(IntegrationService.SECRET_PREFIX);
    }

    @Test
    @DisplayName("reading another tenant's endpoint is a not found, not a forbidden")
    void anotherTenantsEndpointIsNotFound() {
        when(repository.findByIdAndOrganizationId(any(UUID.class), eq(organizationId)))
                .thenReturn(Optional.empty());

        // Not-found rather than forbidden, so the existence of another tenant's endpoint
        // is not revealed by the difference between the two responses.
        assertThatThrownBy(() -> service.get(UUID.randomUUID()))
                .isInstanceOf(PlatformExceptions.NotFound.class);
    }

    @Test
    @DisplayName("pausing stops deliveries without discarding the subscription")
    void pauseKeepsTheSubscription() {
        WebhookEndpoint endpoint = newEndpoint("https://hooks.acme.example/hatis");
        when(repository.findByIdAndOrganizationId(endpoint.getId(), organizationId))
                .thenReturn(Optional.of(endpoint));
        when(repository.save(any(WebhookEndpoint.class))).thenAnswer(i -> i.getArgument(0));

        WebhookEndpointService.EndpointView view = service.pause(endpoint.getId());

        assertThat(view.active()).isFalse();
        assertThat(endpoint.isActive()).isFalse();
        // The secret and subscription survive, so resuming needs nothing from the customer.
        assertThat(endpoint.getSecretCiphertext()).isEqualTo(CIPHERTEXT);
        assertThat(endpoint.getEvents()).containsExactly("content.published");
    }

    @Test
    @DisplayName("a new secret has 32 bytes of entropy behind the prefix")
    void theSecretCarriesEnoughEntropy() {
        String secret = WebhookEndpointService.newSecret();

        assertThat(secret).startsWith(IntegrationService.SECRET_PREFIX);
        String body = secret.substring(IntegrationService.SECRET_PREFIX.length());
        // 32 bytes, base64url without padding: ceil(32 * 4 / 3) characters.
        assertThat(body).hasSize(43);
        assertThat(WebhookEndpointService.newSecret()).isNotEqualTo(secret);
    }

    private void stubKeyAndEncryption() {
        when(tenantKeys.keyForOrganization(organizationId))
                .thenReturn(new TenantKeyService.TenantKey(WRAPPED_DEK, KEY_ID));
        when(encryption.encryptWith(anyString(), anyString(), anyString())).thenReturn(CIPHERTEXT);
    }

    private WebhookEndpoint newEndpoint(String url) {
        return WebhookEndpoint.register(organizationId, url, null,
                List.of("content.published"), CIPHERTEXT, KEY_ID);
    }
}
