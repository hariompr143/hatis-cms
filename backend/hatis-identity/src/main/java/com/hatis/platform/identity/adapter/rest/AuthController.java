package com.hatis.platform.identity.adapter.rest;

import com.hatis.platform.identity.application.ApiKeyService;
import com.hatis.platform.identity.application.AuthenticationService;
import com.hatis.platform.identity.application.MfaService;
import com.hatis.platform.shared.tenant.TenantContext;
import com.hatis.platform.shared.tenant.TenantContextHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Authentication, MFA and API key endpoints.
 *
 * <p>These are the only endpoints in the platform reachable without a bearer
 * token, and each is rate limited per IP and per email to blunt credential
 * stuffing.
 */
@RestController
@Tag(name = "Authentication", description = "Sign-in, MFA, sessions and API keys")
public class AuthController {

    private final AuthenticationService authentication;
    private final MfaService mfa;
    private final ApiKeyService apiKeys;

    public AuthController(AuthenticationService authentication, MfaService mfa, ApiKeyService apiKeys) {
        this.authentication = authentication;
        this.mfa = mfa;
        this.apiKeys = apiKeys;
    }

    @PostMapping("/v1/auth/sign-up")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a user account")
    public AuthenticationService.SignUpResult signUp(
            @Valid @RequestBody AuthenticationService.SignUpCommand command) {
        return authentication.signUp(command);
    }

    @PostMapping("/v1/auth/sign-in")
    @Operation(summary = "Sign in with email and password",
            description = "Returns a session, or an MFA challenge when a second factor is enrolled. "
                    + "An unknown email and a wrong password are indistinguishable.")
    public AuthenticationService.SignInResult signIn(
            @Valid @RequestBody AuthenticationService.SignInCommand command) {
        return authentication.signIn(command);
    }

    @PostMapping("/v1/auth/mfa/verify")
    @Operation(summary = "Complete sign-in with a TOTP code")
    public AuthenticationService.SignInResult verifyMfa(
            @Valid @RequestBody AuthenticationService.VerifyMfaCommand command) {
        return authentication.verifyMfa(command);
    }

    @PostMapping("/v1/auth/refresh")
    @Operation(summary = "Rotate a refresh token",
            description = "Reusing a token that was already rotated revokes the whole session family.")
    public AuthenticationService.SignInResult refresh(
            @Valid @RequestBody AuthenticationService.RefreshCommand command) {
        return authentication.refresh(command);
    }

    @PostMapping("/v1/auth/sign-out")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void signOut(@Valid @RequestBody AuthenticationService.RefreshCommand command) {
        authentication.signOut(command.refreshToken());
    }

    @PostMapping("/v1/auth/password")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Change the password",
            description = "Revokes every active session for the user.")
    public void changePassword(@Valid @RequestBody AuthenticationService.ChangePasswordCommand command) {
        authentication.changePassword(currentUserId(), command);
    }

    // --- MFA ------------------------------------------------------------------

    @PostMapping("/v1/auth/mfa/enrolment")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Begin TOTP enrolment",
            description = "The secret and provisioning URI are returned once and never again.")
    public MfaService.EnrolmentChallenge beginMfa(@RequestBody(required = false) EnrolmentRequest request) {
        String issuer = request == null || request.issuer() == null ? "HATIS" : request.issuer();
        return mfa.beginEnrolment(currentUserId(), issuer);
    }

    @PostMapping("/v1/auth/mfa/enrolment/{enrolmentId}/verify")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Complete TOTP enrolment and receive recovery codes")
    public MfaService.EnrolmentResult verifyMfaEnrolment(@PathVariable UUID enrolmentId,
                                                         @Valid @RequestBody MfaVerifyRequest request) {
        return mfa.verify(currentUserId(), enrolmentId, request.code());
    }

    @DeleteMapping("/v1/auth/mfa")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disableMfa() {
        mfa.disable(currentUserId());
    }

    // --- API keys ---------------------------------------------------------------

    @GetMapping("/v1/api-keys")
    @PreAuthorize("hasPermission(null, 'ORGANIZATION', 'api_key:read')")
    public List<ApiKeyService.ApiKeyView> listApiKeys() {
        return apiKeys.list();
    }

    @PostMapping("/v1/api-keys")
    @PreAuthorize("hasPermission(null, 'ORGANIZATION', 'api_key:write')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an API key",
            description = "The plaintext key is returned in this response only. It is stored hashed.")
    public ApiKeyService.CreatedApiKey createApiKey(
            @Valid @RequestBody ApiKeyService.CreateApiKeyCommand command) {
        return apiKeys.create(command);
    }

    @DeleteMapping("/v1/api-keys/{keyId}")
    @PreAuthorize("hasPermission(null, 'ORGANIZATION', 'api_key:write')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeApiKey(@PathVariable UUID keyId) {
        apiKeys.revoke(keyId);
    }

    @GetMapping("/v1/service-accounts")
    @PreAuthorize("hasPermission(null, 'ORGANIZATION', 'api_key:read')")
    public List<ApiKeyService.ServiceAccountView> listServiceAccounts() {
        return apiKeys.listServiceAccounts();
    }

    @PostMapping("/v1/service-accounts")
    @PreAuthorize("hasPermission(null, 'ORGANIZATION', 'api_key:write')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiKeyService.ServiceAccountView createServiceAccount(
            @Valid @RequestBody ApiKeyService.CreateServiceAccountCommand command) {
        return apiKeys.createServiceAccount(command);
    }

    @GetMapping("/v1/auth/session")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Describe the current session")
    public ResponseEntity<SessionView> session() {
        TenantContext context = TenantContextHolder.require();
        return ResponseEntity.ok(new SessionView(
                context.principalId(),
                context.organizationId(),
                context.principalType().name()));
    }

    private static UUID currentUserId() {
        return TenantContextHolder.require().principalId();
    }

    public record EnrolmentRequest(String issuer) {
    }

    public record MfaVerifyRequest(String code) {
    }

    public record SessionView(UUID userId, UUID organizationId, String principalType) {
    }
}
