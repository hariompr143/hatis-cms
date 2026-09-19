package com.hatis.platform.identity.application;

import com.hatis.platform.identity.adapter.persistence.ApiKeyRepository;
import com.hatis.platform.identity.adapter.persistence.ServiceAccountRepository;
import com.hatis.platform.identity.domain.ApiKey;
import com.hatis.platform.identity.domain.ServiceAccount;
import com.hatis.platform.shared.audit.AuditRecord;
import com.hatis.platform.shared.audit.AuditRecorder;
import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.quota.QuotaKey;
import com.hatis.platform.shared.quota.QuotaService;
import com.hatis.platform.shared.tenant.TenantContextHolder;
import com.hatis.platform.shared.tenant.TenantTransactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * API key and service account management.
 *
 * <p>The plaintext key is generated here and returned exactly once. Only a SHA-256
 * hash is stored, so neither a database leak nor an operator with database read
 * access can obtain a working credential.
 */
@Service
public class ApiKeyService {

    public static final String KEY_PREFIX = "hatis_";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiKeyRepository apiKeys;
    private final ServiceAccountRepository serviceAccounts;
    private final QuotaService quotas;
    private final AuditRecorder audit;

    public ApiKeyService(ApiKeyRepository apiKeys,
                         ServiceAccountRepository serviceAccounts,
                         QuotaService quotas,
                         AuditRecorder audit) {
        this.apiKeys = apiKeys;
        this.serviceAccounts = serviceAccounts;
        this.quotas = quotas;
        this.audit = audit;
    }

    @TenantTransactional
    public CreatedApiKey create(@Valid CreateApiKeyCommand command) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        UUID createdBy = TenantContextHolder.require().principalId();
        quotas.check(organizationId, QuotaKey.API_KEYS, 1);

        ServiceAccount account = serviceAccounts.findByIdAndOrganizationId(command.serviceAccountId(), organizationId)
                .orElseThrow(() -> new PlatformExceptions.NotFound("Service account", command.serviceAccountId()));
        if (!account.isActive()) {
            throw new PlatformExceptions.StateConflict("That service account is disabled");
        }

        Instant expiresAt = command.expiresInDays() == null
                ? null
                : Instant.now().plus(command.expiresInDays(), ChronoUnit.DAYS);

        String plaintext = generateKey();
        ApiKey key = apiKeys.save(new ApiKey(
                organizationId,
                command.name(),
                AuthenticationService.hash(plaintext),
                plaintext.substring(0, Math.min(12, plaintext.length())),
                command.scopes(),
                account.getId(),
                expiresAt,
                createdBy));

        quotas.record(organizationId, QuotaKey.API_KEYS, 1);
        audit.record(AuditRecord.builder("api_key.created")
                .resource("api_key", key.getId())
                .metadata(Map.of("name", key.getName(), "prefix", key.getKeyPrefix()))
                .build());
        // The plaintext appears in this response and nowhere else.
        return new CreatedApiKey(ApiKeyView.from(key), plaintext);
    }

    @TenantTransactional(readOnly = true)
    public List<ApiKeyView> list() {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        return apiKeys.findByOrganizationIdOrderByCreatedAtDesc(organizationId, PageRequest.of(0, 200))
                .stream().map(ApiKeyView::from).toList();
    }

    @TenantTransactional
    public void revoke(UUID keyId) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        ApiKey key = apiKeys.findByIdAndOrganizationId(keyId, organizationId)
                .orElseThrow(() -> new PlatformExceptions.NotFound("API key", keyId));
        key.revoke();
        apiKeys.save(key);
        audit.record(AuditRecord.builder("api_key.revoked")
                .resource("api_key", keyId)
                .build());
    }

    @TenantTransactional
    public ServiceAccountView createServiceAccount(@Valid CreateServiceAccountCommand command) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        ServiceAccount account = serviceAccounts.save(
                new ServiceAccount(organizationId, command.name(), command.description()));
        audit.record(AuditRecord.builder("service_account.created")
                .resource("service_account", account.getId())
                .build());
        return ServiceAccountView.from(account);
    }

    @TenantTransactional(readOnly = true)
    public List<ServiceAccountView> listServiceAccounts() {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        return serviceAccounts.findByOrganizationId(organizationId)
                .stream().map(ServiceAccountView::from).toList();
    }

    /** Called by the authentication filter on a successful API key request. */
    public void recordUse(ApiKey key) {
        key.recordUse(Instant.now());
        apiKeys.save(key);
    }

    private static String generateKey() {
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        return KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    public record CreateApiKeyCommand(@NotBlank @Size(max = 120) String name,
                                      @NotNull UUID serviceAccountId,
                                      List<String> scopes,
                                      Integer expiresInDays) {
    }

    public record CreateServiceAccountCommand(@NotBlank @Size(max = 120) String name,
                                              @Size(max = 2000) String description) {
    }

    /** Never contains the key itself — only the prefix needed to recognise it. */
    public record ApiKeyView(UUID id, String name, String prefix, List<String> scopes,
                             UUID serviceAccountId, Instant lastUsedAt, Instant expiresAt,
                             Instant revokedAt, Instant createdAt) {

        public static ApiKeyView from(ApiKey key) {
            return new ApiKeyView(key.getId(), key.getName(), key.getKeyPrefix(), key.scopeList(),
                    key.getServiceAccountId(), key.getLastUsedAt(), key.getExpiresAt(),
                    key.getRevokedAt(), key.getCreatedAt());
        }
    }

    public record CreatedApiKey(ApiKeyView key, String plaintext) {
    }

    public record ServiceAccountView(UUID id, String name, String description, String status) {
        public static ServiceAccountView from(ServiceAccount a) {
            return new ServiceAccountView(a.getId(), a.getName(), a.getDescription(), a.getStatus().name());
        }
    }
}
