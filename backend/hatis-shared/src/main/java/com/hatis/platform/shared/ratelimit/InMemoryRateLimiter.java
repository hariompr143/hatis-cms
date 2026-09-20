package com.hatis.platform.shared.ratelimit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Single-node token bucket limiter.
 *
 * <p>Correct for one replica and for private single-node installations. In a
 * multi-replica deployment {@code RedisRateLimiter} must be selected, otherwise
 * each replica enforces its own copy of the limit and the effective ceiling is
 * {@code replicas × limit}.
 */
@Component
@ConditionalOnProperty(name = "hatis.rate-limit.backend", havingValue = "memory", matchIfMissing = true)
public class InMemoryRateLimiter implements RateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Counter evictions;

    public InMemoryRateLimiter(MeterRegistry meterRegistry) {
        this.evictions = Counter.builder("hatis.ratelimit.buckets.evicted").register(meterRegistry);
    }

    @Override
    public RateLimitDecision tryConsume(String key, int limit, int burst) {
        long now = System.nanoTime();
        Bucket bucket = buckets.compute(key, (k, existing) -> {
            if (existing == null) {
                return new Bucket(burst, now);
            }
            existing.refill(burst, now);
            return existing;
        });
        // Bound memory: a burst of unique keys must not grow the map without limit.
        if (buckets.size() > 100_000) {
            buckets.clear();
            evictions.increment();
        }
        if (bucket.take()) {
            return RateLimitDecision.allow(limit, (int) bucket.available.get(), WINDOW);
        }
        return RateLimitDecision.deny(limit, WINDOW);
    }

    @Override
    public String name() {
        return "memory";
    }

    private static final class Bucket {
        private final AtomicLong available;
        private volatile long lastRefillNanos;

        Bucket(int capacity, long now) {
            this.available = new AtomicLong(capacity);
            this.lastRefillNanos = now;
        }

        synchronized void refill(int capacity, long now) {
            long elapsedNanos = now - lastRefillNanos;
            if (elapsedNanos <= 0) {
                return;
            }
            long tokensToAdd = elapsedNanos * capacity / WINDOW.toNanos();
            if (tokensToAdd > 0) {
                available.set(Math.min(capacity, available.get() + tokensToAdd));
                lastRefillNanos = now;
            }
        }

        boolean take() {
            while (true) {
                long current = available.get();
                if (current <= 0) {
                    return false;
                }
                if (available.compareAndSet(current, current - 1)) {
                    return true;
                }
            }
        }
    }
}
