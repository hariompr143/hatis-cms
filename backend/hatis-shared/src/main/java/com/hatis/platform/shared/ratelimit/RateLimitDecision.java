package com.hatis.platform.shared.ratelimit;

import java.time.Duration;

/**
 * Outcome of a rate limit check.
 *
 * @param allowed       whether the request may proceed
 * @param limit         configured requests per window
 * @param remaining     requests left in the current window (never negative)
 * @param resetIn       time until the window resets
 */
public record RateLimitDecision(boolean allowed, int limit, int remaining, Duration resetIn) {

    public static RateLimitDecision allow(int limit, int remaining, Duration resetIn) {
        return new RateLimitDecision(true, limit, Math.max(0, remaining), resetIn);
    }

    public static RateLimitDecision deny(int limit, Duration resetIn) {
        return new RateLimitDecision(false, limit, 0, resetIn);
    }

    public static RateLimitDecision disabled() {
        return new RateLimitDecision(true, Integer.MAX_VALUE, Integer.MAX_VALUE, Duration.ZERO);
    }
}
