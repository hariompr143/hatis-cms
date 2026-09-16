package com.hatis.platform.shared.tenant;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a use case that must run inside a transaction with the caller's tenant
 * bound as a transaction-local PostgreSQL setting, so that row level security
 * policies apply to every statement — including native ones.
 *
 * <p>Applied by {@code TenantRlsAspect}. This is the second isolation layer; the
 * first is the explicit {@code organizationId} parameter on every repository
 * method.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TenantTransactional {

    /** Set to {@code false} for read-only use cases so replicas can be used. */
    boolean readOnly() default false;

    /**
     * Allow execution without a bound tenant. Only platform-level jobs (usage
     * rollup, certificate renewal sweep) may set this, and every such use is
     * audited.
     */
    boolean allowPlatformPrincipal() default false;
}
