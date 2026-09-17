package com.hatis.platform.security;

import com.hatis.platform.identity.application.TokenService;
import com.hatis.platform.shared.api.GlobalExceptionHandler;
import com.hatis.platform.shared.tenant.TenantContext;
import com.hatis.platform.shared.tenant.TenantContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Establishes the authenticated principal and the tenant context.
 *
 * <p>This filter is the only place in the platform where a tenant becomes "known".
 * It derives the tenant exclusively from the verified token; a header, query
 * parameter or body value can never introduce one. Every later layer — service,
 * repository, row level security — trusts what is bound here.
 *
 * <p>The context is always cleared in {@code finally}, so a pooled thread cannot
 * serve the next request under the previous caller's tenant.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenService tokens;

    public JwtAuthenticationFilter(TokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        TokenService.VerifiedToken token;
        try {
            token = tokens.verify(header.substring(BEARER_PREFIX.length()).trim());
        } catch (RuntimeException e) {
            // An invalid token is not an error the client needs explained in
            // detail; 401 with a generic message, and the reason goes to the log.
            logger.debug("Rejected bearer token: " + e.getMessage());
            filterChain.doFilter(request, response);
            return;
        }

        TenantContext context = new TenantContext(
                token.organizationId(),
                token.userId(),
                TenantContext.PrincipalType.USER,
                null,
                null,
                MDC.get(GlobalExceptionHandler.CORRELATION_ID_KEY),
                false);
        TenantContextHolder.set(context);
        try {
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    token.userId(),
                    null,
                    token.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList());
            authentication.setDetails(new AuthenticatedPrincipal(token, List.copyOf(token.authenticationMethods())));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
            TenantContextHolder.clear();
        }
    }

    /** Attached to the Spring Security authentication so handlers can read the token. */
    public record AuthenticatedPrincipal(TokenService.VerifiedToken token, List<String> authenticationMethods) {
    }
}
