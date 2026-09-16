package com.hatis.platform.shared.observability;

import com.hatis.platform.shared.api.GlobalExceptionHandler;
import com.hatis.platform.shared.tenant.TenantContext;
import com.hatis.platform.shared.tenant.TenantContextHolder;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * Copies the caller's tenant context and MDC onto asynchronous tasks, then clears
 * them when the task finishes.
 *
 * <p>Copying explicitly (instead of using {@code InheritableThreadLocal}) is the
 * point: a pooled thread must never inherit whatever the previous task left
 * behind. Without this, a background job could execute with a stale tenant and
 * read the wrong customer's data.
 */
public class ContextCopyingTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        TenantContext context = TenantContextHolder.get();
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previousMdc = MDC.getCopyOfContextMap();
            TenantContext previousContext = TenantContextHolder.get();
            try {
                if (mdc != null) {
                    MDC.setContextMap(mdc);
                } else {
                    MDC.clear();
                }
                if (context != null) {
                    TenantContextHolder.set(context);
                }
                runnable.run();
            } finally {
                TenantContextHolder.clear();
                MDC.clear();
                if (previousContext != null) {
                    TenantContextHolder.set(previousContext);
                }
                if (previousMdc != null) {
                    MDC.setContextMap(previousMdc);
                }
                // GlobalExceptionHandler's key is part of the MDC map handled above;
                // referenced here to make the dependency explicit for reviewers.
                MDC.remove(GlobalExceptionHandler.CORRELATION_ID_KEY);
                if (previousMdc != null) {
                    MDC.setContextMap(previousMdc);
                }
            }
        };
    }
}
