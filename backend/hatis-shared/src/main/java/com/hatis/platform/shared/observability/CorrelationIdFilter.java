package com.hatis.platform.shared.observability;

import com.hatis.platform.shared.api.GlobalExceptionHandler;
import com.hatis.platform.shared.id.Identifiers;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Establishes a correlation id for every request and puts it on the MDC and the
 * response.
 *
 * <p>An incoming {@code X-Correlation-Id} is accepted only when it is a plausible
 * identifier, so a client cannot inject control characters into log lines. The
 * resolved id is echoed in the response header so a customer can quote it in a
 * support ticket and an operator can find every log line, audit record and event
 * for that request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String correlationId = isUsable(incoming) ? incoming : Identifiers.newIdString();
        MDC.put(GlobalExceptionHandler.CORRELATION_ID_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(GlobalExceptionHandler.CORRELATION_ID_KEY);
        }
    }

    private static boolean isUsable(String value) {
        return value != null
                && value.length() >= 8
                && value.length() <= 64
                && value.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '-' || c == '_');
    }
}
