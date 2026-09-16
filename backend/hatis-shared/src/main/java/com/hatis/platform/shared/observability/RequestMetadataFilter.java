package com.hatis.platform.shared.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Captures the client address and user agent for the duration of the request.
 *
 * <p>{@code X-Forwarded-For} is trusted only when {@code hatis.trust-proxy} is
 * true, which is set for the platform's own ingress and never for a directly
 * exposed instance. Otherwise a client could write a forged address into the
 * audit trail.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestMetadataFilter extends OncePerRequestFilter {

    private final boolean trustProxy;

    public RequestMetadataFilter(@Value("${hatis.trust-proxy:false}") boolean trustProxy) {
        this.trustProxy = trustProxy;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        RequestMetadataHolder.set(new RequestMetadata(
                clientIp(request),
                truncate(request.getHeader("User-Agent"), 512),
                request.getMethod(),
                truncate(request.getRequestURI(), 512),
                truncate(request.getHeader("Origin"), 255)));
        try {
            filterChain.doFilter(request, response);
        } finally {
            RequestMetadataHolder.clear();
        }
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (trustProxy && forwarded != null && !forwarded.isBlank()) {
            // Left-most entry is the original client; the rest are proxies.
            return truncate(forwarded.split(",")[0].trim(), 64);
        }
        return request.getRemoteAddr();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
