package com.hatis.platform.shared.ratelimit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Distributed token bucket backed by Redis, evaluated atomically by a Lua script
 * so concurrent requests from different replicas cannot over-consume.
 *
 * <p>Fails open when Redis is unreachable: losing rate limiting for a few seconds
 * is a smaller incident than an outage, and the failure is counted so operators
 * can see the degradation.
 */
@Component
@ConditionalOnProperty(name = "hatis.rate-limit.backend", havingValue = "redis")
public class RedisRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    /**
     * Token bucket in one atomic round trip.
     * Returns { allowed, remaining, retryAfterSeconds }.
     */
    private static final DefaultRedisScript<List> SCRIPT = new DefaultRedisScript<>("""
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill_per_sec = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local requested = tonumber(ARGV[4])

            local state = redis.call('HMGET', key, 'tokens', 'ts')
            local tokens = tonumber(state[1])
            local ts = tonumber(state[2])
            if tokens == nil then
              tokens = capacity
              ts = now
            end

            local delta = math.max(0, now - ts)
            tokens = math.min(capacity, tokens + delta * refill_per_sec)

            local allowed = 0
            local retry_after = 0
            if tokens >= requested then
              tokens = tokens - requested
              allowed = 1
            else
              retry_after = math.ceil((requested - tokens) / refill_per_sec)
            end

            redis.call('HMSET', key, 'tokens', tokens, 'ts', now)
            redis.call('EXPIRE', key, math.ceil(capacity / refill_per_sec) * 2)
            return { allowed, math.floor(tokens), retry_after }
            """, List.class);

    private final StringRedisTemplate redis;
    private final Counter backendFailures;

    public RedisRateLimiter(StringRedisTemplate redis, MeterRegistry meterRegistry) {
        this.redis = redis;
        this.backendFailures = Counter.builder("hatis.ratelimit.backend_failures")
                .tag("backend", "redis")
                .register(meterRegistry);
    }

    @Override
    @SuppressWarnings("unchecked")
    public RateLimitDecision tryConsume(String key, int limit, int burst) {
        double refillPerSecond = limit / 60.0d;
        try {
            List<Long> result = (List<Long>) redis.execute(
                    SCRIPT,
                    List.of("hatis:rl:" + key),
                    String.valueOf(burst),
                    String.valueOf(refillPerSecond),
                    String.valueOf(System.currentTimeMillis() / 1000d),
                    "1");
            if (result == null || result.size() < 3) {
                backendFailures.increment();
                return RateLimitDecision.allow(limit, burst, Duration.ofMinutes(1));
            }
            // Redis returns the multi-bulk reply as Long or String depending on the
            // template's serialisers, so parse defensively rather than assume.
            boolean allowed = toLong(result.get(0)) == 1L;
            int remaining = (int) toLong(result.get(1));
            long retryAfter = toLong(result.get(2));
            if (allowed) {
                return RateLimitDecision.allow(limit, remaining, Duration.ofMinutes(1));
            }
            return RateLimitDecision.deny(limit, Duration.ofSeconds(Math.max(1, retryAfter)));
        } catch (RuntimeException e) {
            backendFailures.increment();
            log.warn("Rate limit backend unavailable, failing open: {}", e.getMessage());
            return RateLimitDecision.allow(limit, burst, Duration.ofMinutes(1));
        }
    }

    @Override
    public String name() {
        return "redis";
    }

    private static long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? 0L : Long.parseLong(String.valueOf(value));
    }
}
