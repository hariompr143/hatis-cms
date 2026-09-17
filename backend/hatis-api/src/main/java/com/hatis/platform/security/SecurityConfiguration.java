package com.hatis.platform.security;

import com.hatis.platform.shared.api.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Security filter chain.
 *
 * <p>Stateless by design: no server-side HTTP session, no JSESSIONID, no cookie
 * that can be fixed. Access tokens are short-lived and signed; refresh tokens are
 * rotating and stored hashed.
 *
 * <p>CSRF is disabled for the bearer-token API because there is no ambient
 * credential for a browser to attach automatically. If a cookie-authenticated
 * surface is added, CSRF protection must be enabled for it — the token repository
 * bean is already wired so that is a configuration change, not a rewrite.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {

    /** bcrypt cost 12: deliberate. Sign-in is rate limited, so the cost is affordable. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public MethodSecurityExpressionHandler methodSecurityExpressionHandler(HatisPermissionEvaluator evaluator) {
        DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
        handler.setPermissionEvaluator(evaluator);
        return handler;
    }

    @Bean
    public CookieCsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> cookie.secure(true).sameSite("Strict"));
        return repository;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtAuthenticationFilter jwtFilter,
                                           ObjectMapper objectMapper) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .headers(headers -> headers
                .frameOptions(frame -> frame.deny())
                .contentTypeOptions(contentType -> { })
                .httpStrictTransportSecurity(hsts -> hsts
                    .maxAgeInSeconds(31_536_000)
                    .includeSubDomains(true)
                    .preload(true))
                .referrerPolicy(referrer -> referrer
                    .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                .permissionsPolicy(permissions -> permissions
                    .policy("geolocation=(), microphone=(), camera=()")))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/v1/auth/sign-up",
                    "/v1/auth/sign-in",
                    "/v1/auth/mfa/verify",
                    "/v1/auth/refresh",
                    "/v1/auth/sign-out",
                    "/internal/health/**",
                    "/internal/info",
                    "/v3/api-docs/**",
                    "/docs/**").permitAll()
                .requestMatchers("/internal/**").hasRole("PLATFORM_OPERATOR")
                .anyRequest().authenticated())
            .exceptionHandling(handling -> handling
                .authenticationEntryPoint((request, response, ex) ->
                    writeError(objectMapper, response, HttpStatus.UNAUTHORIZED,
                            "unauthenticated", "Authentication is required for this endpoint"))
                .accessDeniedHandler((request, response, ex) ->
                    writeError(objectMapper, response, HttpStatus.FORBIDDEN,
                            "forbidden", "You do not have access to this resource")))
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        // No wildcard origin with credentials. The console is served from a known
        // origin; anything else must be added explicitly.
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("https://*.hatis.example"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key",
                "X-Correlation-Id", "X-CSRF-Token"));
        configuration.setExposedHeaders(List.of("X-Correlation-Id", "X-RateLimit-Limit",
                "X-RateLimit-Remaining", "X-RateLimit-Reset"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private static void writeError(ObjectMapper objectMapper,
                                   HttpServletResponse response,
                                   HttpStatus status,
                                   String code,
                                   String message) {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        try {
            objectMapper.writeValue(response.getWriter(), ApiError.of(code, message, null));
        } catch (Exception e) {
            // Falling back to an empty body is safer than leaking an exception.
            response.setStatus(status.value());
        }
    }
}
