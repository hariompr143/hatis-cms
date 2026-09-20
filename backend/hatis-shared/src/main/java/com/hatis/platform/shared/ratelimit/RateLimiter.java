package com.hatis.platform.shared.ratelimit;

/**
 * Rate limiting port.
 *
 * <p>Limits are applied at several granularities simultaneously — organization,
 * principal, API key and client IP — and the plan determines each ceiling. The
 * strictest applicable limit wins.
 *
 * <p>Implementations must fail <em>open</em> on their own infrastructure failure
 * (a Redis outage must not take the API down) while recording
 * {@code hatis_ratelimit_backend_failures_total} so the degradation is visible.
 */
public interface RateLimiter {

    RateLimitDecision tryConsume(String key, int limit, int burst);

    String name();
}
