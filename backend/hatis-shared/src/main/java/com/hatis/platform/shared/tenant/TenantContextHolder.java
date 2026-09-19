package com.hatis.platform.shared.tenant;

import com.hatis.platform.shared.error.PlatformExceptions;

/**
 * Thread-bound holder for the current {@link TenantContext}.
 *
 * <p>The value is always cleared in a {@code finally} block by the filter that
 * set it, and is copied explicitly (never inherited) by {@code TaskDecorator}s
 * for asynchronous work, so a pooled thread can never serve a request with the
 * previous request's tenant.
 */
public final class TenantContextHolder {

    private static final ThreadLocal<TenantContext> CONTEXT = new ThreadLocal<>();

    private TenantContextHolder() {
    }

    public static void set(TenantContext context) {
        CONTEXT.set(context);
    }

    public static TenantContext get() {
        return CONTEXT.get();
    }

    public static TenantContext require() {
        TenantContext context = CONTEXT.get();
        if (context == null) {
            throw new PlatformExceptions.Unauthenticated("No tenant context is bound to this thread");
        }
        return context;
    }

    public static boolean isPresent() {
        return CONTEXT.get() != null;
    }

    public static void clear() {
        CONTEXT.remove();
    }

    /** Runs {@code action} with {@code context} bound, restoring the previous value afterwards. */
    public static <T> T callAs(TenantContext context, java.util.function.Supplier<T> action) {
        TenantContext previous = CONTEXT.get();
        CONTEXT.set(context);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CONTEXT.remove();
            } else {
                CONTEXT.set(previous);
            }
        }
    }

    public static void runAs(TenantContext context, Runnable action) {
        callAs(context, () -> {
            action.run();
            return null;
        });
    }
}
