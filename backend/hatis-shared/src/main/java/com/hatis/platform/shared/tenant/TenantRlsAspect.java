package com.hatis.platform.shared.tenant;

import com.hatis.platform.shared.error.PlatformExceptions;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Binds the current tenant to the database transaction so that PostgreSQL row
 * level security policies take effect.
 *
 * <p>{@code set_config(..., true)} is transaction-local, so a connection returned
 * to the pool cannot carry a tenant into the next borrower. The application role
 * runs with {@code BYPASSRLS} off and the policies use {@code FORCE ROW LEVEL
 * SECURITY}, which means the defence holds even for queries written by someone
 * who forgot the {@code organizationId} predicate.
 *
 * <p>When no tenant is bound the setting is left unset; because the policies
 * compare against {@code null}, such a transaction sees zero tenant rows rather
 * than all of them. Failing closed is the intent.
 */
@Aspect
@Component
@Order(0)
public class TenantRlsAspect {

    /** Name of the transaction-local PostgreSQL setting referenced by every RLS policy. */
    public static final String RLS_SETTING = "hatis.organization_id";

    private static final Logger log = LoggerFactory.getLogger(TenantRlsAspect.class);

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public TenantRlsAspect(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Around("@annotation(tenantTransactional)")
    public Object bindTenantToTransaction(ProceedingJoinPoint joinPoint, TenantTransactional tenantTransactional)
            throws Throwable {
        TenantContext context = TenantContextHolder.get();
        if (context == null) {
            if (tenantTransactional.allowPlatformPrincipal()) {
                return joinPoint.proceed();
            }
            throw new PlatformExceptions.Unauthenticated("No tenant context is bound to this request");
        }
        UUID organizationId = context.organizationId();
        if (organizationId == null && !tenantTransactional.allowPlatformPrincipal()) {
            throw new PlatformExceptions.Forbidden("This operation requires a tenant-scoped principal");
        }

        transactionTemplate.setReadOnly(tenantTransactional.readOnly());
        try {
            return transactionTemplate.execute(status -> {
                if (organizationId != null) {
                    jdbcTemplate.update("select set_config(?, ?, true)", RLS_SETTING, organizationId.toString());
                } else {
                    // Explicitly unset so a pooled connection can never leak a tenant.
                    jdbcTemplate.update("select set_config(?, null, true)", RLS_SETTING);
                    log.debug("Executing platform-principal transaction without tenant binding");
                }
                try {
                    return joinPoint.proceed();
                } catch (RuntimeException e) {
                    throw e;
                } catch (Throwable t) {
                    throw new IllegalStateException(t);
                }
            });
        } finally {
            transactionTemplate.setReadOnly(false);
        }
    }
}
