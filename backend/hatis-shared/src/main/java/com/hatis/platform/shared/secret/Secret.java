package com.hatis.platform.shared.secret;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * A secret value in memory.
 *
 * <p>Exists so that a secret can never be turned into a string by accident:
 * {@link #toString()} is fixed, Jackson never sees it (it is not a bean
 * property), and log statements that interpolate it print {@code [REDACTED]}.
 *
 * <p>{@link #destroy()} zeroes the backing array; call it as soon as the value is
 * no longer needed so a heap dump cannot recover it.
 */
public final class Secret implements AutoCloseable {

    private static final Secret EMPTY = new Secret(new byte[0]);

    private final byte[] value;
    private volatile boolean destroyed;

    private Secret(byte[] value) {
        this.value = Objects.requireNonNull(value, "secret value");
    }

    public static Secret of(String plaintext) {
        return new Secret(plaintext == null ? new byte[0] : plaintext.getBytes(StandardCharsets.UTF_8));
    }

    public static Secret of(byte[] bytes) {
        return new Secret(bytes.clone());
    }

    public static Secret empty() {
        return EMPTY;
    }

    public boolean isEmpty() {
        return value.length == 0;
    }

    /** Returns the plaintext. Callers must not log, persist or return the result. */
    public String reveal() {
        if (destroyed) {
            throw new IllegalStateException("Secret has been destroyed");
        }
        return new String(value, StandardCharsets.UTF_8);
    }

    /** Constant-time comparison, so verification cannot be timed. */
    public boolean matches(String candidate) {
        if (candidate == null) {
            return false;
        }
        byte[] other = candidate.getBytes(StandardCharsets.UTF_8);
        try {
            return java.security.MessageDigest.isEqual(value, other);
        } finally {
            Arrays.fill(other, (byte) 0);
        }
    }

    public void destroy() {
        Arrays.fill(value, (byte) 0);
        destroyed = true;
    }

    @Override
    public void close() {
        destroy();
    }

    @Override
    public String toString() {
        return "[REDACTED]";
    }
}
