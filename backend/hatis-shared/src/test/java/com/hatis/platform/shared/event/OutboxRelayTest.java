package com.hatis.platform.shared.event;

import com.hatis.platform.shared.tenant.TenantContext;
import com.hatis.platform.shared.tenant.TenantContextHolder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * How the relay decides whose work to take, and in whose name.
 *
 * <p>{@code OutboxRelayRlsIT} proves against a real PostgreSQL what the database permits.
 * These tests cover the half the database cannot see: that the relay asks for work per
 * tenant rather than once for everyone, that it binds the right tenant before asking, and
 * that one tenant or one unreadable directory cannot stall the rest.
 *
 * <p>{@link #workIsClaimedOnAnotherBeanSoTheTransactionIsReal} is the regression test for a
 * defect that shipped. The batch loop used to call {@code this.publishOne(entry)}, and
 * because Spring applies {@code @Transactional} and {@code @TenantTransactional} through a
 * proxy, a call that never leaves the bean is a call with no transaction and no tenant
 * setting — which, against a table that fails closed, means no rows and no events.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Outbox relay")
class OutboxRelayTest {

    private static final UUID ORG_A = UUID.randomUUID();
    private static final UUID ORG_B = UUID.randomUUID();
    private static final UUID ENTRY = UUID.randomUUID();

    @Mock
    private OutboxWork work;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        relay = new OutboxRelay(work, jdbcTemplate, new SimpleMeterRegistry());
    }

    @AfterEach
    void clearTenant() {
        // The relay binds a tenant on a thread local and restores it, but a failure
        // mid-test would otherwise leak it into the next one.
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("it drains every tenant in the directory, not the table as a whole")
    void itDrainsEveryTenantInTheDirectory() {
        givenDirectory(ORG_A, ORG_B);
        UUID entryA = UUID.randomUUID();
        UUID entryB = UUID.randomUUID();
        when(work.claimFor(ORG_A)).thenReturn(List.of(entryA));
        when(work.claimFor(ORG_B)).thenReturn(List.of(entryB));

        relay.drain();

        verify(work).publishOne(entryA);
        verify(work).publishOne(entryB);
    }

    @Test
    @DisplayName("it binds each tenant before reading that tenant's entries")
    void itBindsEachTenantBeforeReadingItsEntries() {
        givenDirectory(ORG_A);
        AtomicReference<TenantContext> seen = new AtomicReference<>();
        when(work.claimFor(ORG_A)).thenAnswer(invocation -> {
            seen.set(TenantContextHolder.get());
            return List.of();
        });

        relay.drain();

        assertThat(seen.get())
                .as("without a bound tenant the read returns no rows at all, which is the "
                        + "defect this rewrite exists to remove")
                .isNotNull();
        assertThat(seen.get().organizationId()).isEqualTo(ORG_A);
        assertThat(seen.get().principalType()).isEqualTo(TenantContext.PrincipalType.SYSTEM);
        assertThat(TenantContextHolder.isPresent())
                .as("the context must not outlive the sweep")
                .isFalse();
    }

    @Test
    @DisplayName("platform-wide entries are drained with no tenant bound")
    void itDrainsPlatformEntriesWithNoTenantBound() {
        givenDirectory();
        AtomicReference<TenantContext> seen = new AtomicReference<>();
        when(work.claimPlatformWide()).thenAnswer(invocation -> {
            seen.set(TenantContextHolder.get());
            return List.of(ENTRY);
        });

        relay.drain();

        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().organizationId())
                .as("a platform-wide row belongs to no tenant, so none may be bound while "
                        + "it is read")
                .isNull();
        assertThat(seen.get().platformPrincipal()).isTrue();
        verify(work).publishOne(ENTRY);
    }

    @Test
    @DisplayName("work is claimed on another bean, so the transaction is real")
    void workIsClaimedOnAnotherBeanSoTheTransactionIsReal() {
        givenDirectory(ORG_A);
        when(work.claimFor(ORG_A)).thenReturn(List.of(ENTRY));

        relay.drain();

        // Every database touch crosses a bean boundary, which is what puts Spring's proxy —
        // and therefore the transaction and the tenant setting — on the call path. Calling
        // the same methods on `this` would leave these verifications unmet and the entries
        // would be written back with no transaction behind them.
        verify(work).claimFor(ORG_A);
        verify(work).publishOne(ENTRY);
        verify(work).claimPlatformWide();
        verifyNoMoreInteractions(work);
    }

    @Test
    @DisplayName("one tenant failing does not stop the others being published")
    void oneTenantFailingDoesNotStopTheOthers() {
        givenDirectory(ORG_A, ORG_B);
        when(work.claimFor(ORG_A)).thenThrow(new IllegalStateException("connection reset"));
        UUID entryB = UUID.randomUUID();
        when(work.claimFor(ORG_B)).thenReturn(List.of(entryB));

        relay.drain();

        verify(work).publishOne(entryB);
    }

    @Test
    @DisplayName("a tenant whose publish fails does not stop its own remaining entries")
    void oneFailingEntryDoesNotStopTheRestOfTheTenant() {
        givenDirectory(ORG_A);
        UUID second = UUID.randomUUID();
        when(work.claimFor(ORG_A)).thenReturn(List.of(ENTRY, second));
        doThrow(new IllegalStateException("could not save")).when(work).publishOne(ENTRY);

        relay.drain();

        verify(work).publishOne(second);
    }

    @Test
    @DisplayName("a directory it cannot read ends the sweep rather than failing the schedule")
    void anUnreadableDirectoryEndsTheSweep() {
        when(jdbcTemplate.queryForList(any(String.class), eq(UUID.class)))
                .thenThrow(new IllegalStateException("connection refused"));

        relay.drain();

        verifyNoInteractions(work);
    }

    @Test
    @DisplayName("the tenant list comes from the directory, not from the tenant-scoped table")
    void theTenantListComesFromTheDirectory() {
        givenDirectory(ORG_A);
        when(work.claimFor(ORG_A)).thenReturn(List.of());

        relay.drain();

        // org_organizations is itself row level scoped, so reading it unbound returns
        // nothing and the relay would publish no events at all.
        verify(jdbcTemplate).queryForList(
                "select organization_id from plat_tenant_directory order by created_at",
                UUID.class);
    }

    private void givenDirectory(UUID... organizationIds) {
        when(jdbcTemplate.queryForList(any(String.class), eq(UUID.class)))
                .thenReturn(List.of(organizationIds));
    }
}
