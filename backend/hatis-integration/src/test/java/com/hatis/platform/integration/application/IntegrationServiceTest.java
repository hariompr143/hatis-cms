package com.hatis.platform.integration.application;

import com.hatis.platform.authorization.application.AuthorizationService;
import com.hatis.platform.authorization.domain.ScopeType;
import com.hatis.platform.integration.adapter.persistence.IntegrationRepositories;
import com.hatis.platform.integration.domain.InboundIntegration;
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
 * Integration lifecycle and signing-secret handling.
 *
 * <p>The property that matters most here is narrow: the plaintext secret leaves the
 * service exactly once, on connect and on rotate, and never appears in a view, a path or
 * an audit record. Several of these tests exist to fail if that ever changes.
 */
@ExtendWith(MockitoExtension.class)
class IntegrationServiceTest {

    private final UUID organizationId = UUID.randomUUID();
    private final UUID principalId = UUID.randomUUID();

    @Mock
    private IntegrationRepositories.IntegrationRepository repository;
    @Mock
    private SecretStore secrets;
    @Mock
    private AuthorizationService authorization;
    @Mock
    private AuditRecorder audit;

    private IntegrationService service;

    @BeforeEach
    void setUp() {
        TenantContextHolder.set(TenantContext.of(organizationId, principalId,
                TenantContext.PrincipalType.USER));
        service = new IntegrationService(repository, secrets, authorization, audit);
    }

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    private IntegrationService.CreateIntegrationCommand command() {
        return new IntegrationService.CreateIntegrationCommand(
                "github-main", InboundIntegration.Type.GITHUB, "{}");
    }

    @Test
    @DisplayName("a new integration is pending and holds no secret")
    void createsAPendingIntegration() {
        when(repository.findByOrganizationIdAndName(organizationId, "github-main"))
                .thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        IntegrationService.IntegrationView view = service.create(command());

        assertThat(view.status()).isEqualTo(InboundIntegration.Status.PENDING);
        assertThat(view.connected()).isFalse();
        assertThat(view.hasSecret()).isFalse();
        assertThat(view.endpointPath())
                .isEqualTo("/v1/integrations/github/" + organizationId + "/" + view.id() + "/events");
        verify(authorization).require("integration:write", ScopeType.ORGANIZATION, organizationId);
        // Nothing is written to the secret store until the integration is connected.
        verify(secrets, never()).put(anyString(), any());
    }

    @Test
    @DisplayName("a duplicate name within the tenant is refused")
    void refusesDuplicateName() {
        InboundIntegration existing = new InboundIntegration(
                organizationId, InboundIntegration.Type.GITHUB, "github-main", "{}", null);
        when(repository.findByOrganizationIdAndName(organizationId, "github-main"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.create(command()))
                .isInstanceOf(PlatformExceptions.AlreadyExists.class);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("connecting issues a secret, stores it under a path and returns it once")
    void connectIssuesASecret() {
        InboundIntegration pending = new InboundIntegration(
                organizationId, InboundIntegration.Type.GITHUB, "github-main", "{}", null);
        when(repository.findByIdAndOrganizationId(pending.getId(), organizationId))
                .thenReturn(Optional.of(pending));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        IntegrationService.ConnectedIntegration connected = service.connect(pending.getId());

        assertThat(connected.secret()).startsWith(IntegrationService.SECRET_PREFIX);
        assertThat(connected.integration().connected()).isTrue();
        assertThat(connected.integration().hasSecret()).isTrue();

        ArgumentCaptor<Secret> stored = ArgumentCaptor.forClass(Secret.class);
        verify(secrets).put(eq(IntegrationService.secretPath(organizationId, pending.getId())),
                stored.capture());
        assertThat(stored.getValue().reveal()).isEqualTo(connected.secret());
    }

    @Test
    @DisplayName("the view never exposes the secret or the path it is stored under")
    void viewNeverLeaksTheSecret() {
        InboundIntegration pending = new InboundIntegration(
                organizationId, InboundIntegration.Type.GITHUB, "github-main", "{}", null);
        when(repository.findByIdAndOrganizationId(pending.getId(), organizationId))
                .thenReturn(Optional.of(pending));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        IntegrationService.ConnectedIntegration connected = service.connect(pending.getId());
        String view = connected.integration().toString();

        // hasSecret is a boolean, not the secret, and the store path is internal.
        assertThat(view).doesNotContain(connected.secret());
        assertThat(view).doesNotContain("webhook-secret");
        assertThat(connected.integration().hasSecret()).isTrue();
    }

    @Test
    @DisplayName("connecting twice is refused; rotation is the way to change a secret")
    void refusesDoubleConnect() {
        InboundIntegration connected = new InboundIntegration(
                organizationId, InboundIntegration.Type.GITHUB, "github-main", "{}",
                IntegrationService.secretPath(organizationId, UUID.randomUUID()));
        connected.connect();
        when(repository.findByIdAndOrganizationId(connected.getId(), organizationId))
                .thenReturn(Optional.of(connected));

        assertThatThrownBy(() -> service.connect(connected.getId()))
                .isInstanceOf(PlatformExceptions.StateConflict.class);

        verify(secrets, never()).put(anyString(), any());
    }

    @Test
    @DisplayName("rotating produces a different secret at the same path")
    void rotateReplacesTheSecret() {
        String path = IntegrationService.secretPath(organizationId, UUID.randomUUID());
        InboundIntegration connected = new InboundIntegration(
                organizationId, InboundIntegration.Type.GITHUB, "github-main", "{}", path);
        connected.connect();
        when(repository.findByIdAndOrganizationId(connected.getId(), organizationId))
                .thenReturn(Optional.of(connected));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        IntegrationService.ConnectedIntegration first = service.rotate(connected.getId());
        IntegrationService.ConnectedIntegration second = service.rotate(connected.getId());

        assertThat(second.secret()).isNotEqualTo(first.secret());
        verify(secrets).put(eq(path), any(Secret.class));
    }

    @Test
    @DisplayName("disconnecting deletes the stored secret so a leaked one stops working")
    void disconnectDeletesTheSecret() {
        String path = IntegrationService.secretPath(organizationId, UUID.randomUUID());
        InboundIntegration connected = new InboundIntegration(
                organizationId, InboundIntegration.Type.GITHUB, "github-main", "{}", path);
        connected.connect();
        when(repository.findByIdAndOrganizationId(connected.getId(), organizationId))
                .thenReturn(Optional.of(connected));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.disconnect(connected.getId());

        verify(secrets).delete(path);
        assertThat(connected.getStatus()).isEqualTo(InboundIntegration.Status.DISCONNECTED);
        assertThat(connected.acceptsDeliveries()).isFalse();
        assertThat(connected.getCredentialRef()).isNull();
    }

    @Test
    @DisplayName("a failed secret deletion is reported, not swallowed")
    void reportsFailedSecretDeletion() {
        String path = IntegrationService.secretPath(organizationId, UUID.randomUUID());
        InboundIntegration connected = new InboundIntegration(
                organizationId, InboundIntegration.Type.GITHUB, "github-main", "{}", path);
        connected.connect();
        when(repository.findByIdAndOrganizationId(connected.getId(), organizationId))
                .thenReturn(Optional.of(connected));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        org.mockito.Mockito.doThrow(new RuntimeException("store unreachable"))
                .when(secrets).delete(path);

        // An orphaned credential in the secret store has to surface. The integration is
        // already disconnected, so deliveries have stopped either way.
        assertThatThrownBy(() -> service.disconnect(connected.getId()))
                .isInstanceOf(PlatformExceptions.DependencyUnavailable.class);
    }

    @Test
    @DisplayName("every write operation is authorized against the tenant")
    void authorizesEveryOperation() {
        when(repository.findByOrganizationIdAndName(organizationId, "github-main"))
                .thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.create(command());
        service.list();

        verify(authorization).require("integration:write", ScopeType.ORGANIZATION, organizationId);
        verify(authorization).require("integration:read", ScopeType.ORGANIZATION, organizationId);
    }
}
