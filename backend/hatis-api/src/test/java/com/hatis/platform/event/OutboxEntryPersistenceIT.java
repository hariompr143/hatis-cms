package com.hatis.platform.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hatis.platform.shared.event.OutboxEntry;
import com.hatis.platform.shared.event.OutboxRepository;
import com.hatis.platform.shared.event.PlatformEvent;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The outbox write path and the claim queries, against a real PostgreSQL under forced row
 * level security.
 *
 * <h2>Why this test exists</h2>
 *
 * Six entities map a Java {@code String} onto a PostgreSQL {@code jsonb} column through
 * {@code columnDefinition = "jsonb"}: {@code OutboxEntry.payload}, {@code AuditLog.detail},
 * {@code ContentType.fields}, {@code ContentVersion.body},
 * {@code InboundIntegration.configuration} and {@code IdempotencyRecord.response}. None of
 * them had ever been flushed to a database by anything in this repository.
 *
 * <p>That matters because the mapping is a question about the driver, not about the
 * annotation. pgjdbc's {@code stringtype} property defaults to {@code varchar}, so a
 * {@code String} parameter arrives at the server typed as {@code character varying}, and
 * PostgreSQL refuses to assign that to {@code jsonb}:
 *
 * <pre>
 * ERROR: column "payload" is of type jsonb but expression is of type character varying
 * </pre>
 *
 * That was reproduced against this schema with a prepared statement declaring its parameter
 * {@code varchar}; the same statement declaring it {@code unknown} inserts cleanly. Whether
 * Hibernate reaches the driver as one or the other cannot be settled by reading the
 * annotations, so it was settled here - and it reaches it as {@code varchar}, so all seven
 * of the write paths in this class failed on first run. The fix is pgjdbc's
 * {@code stringtype=unspecified}, set on the Hikari pool in {@code application.yml} so that
 * it cannot depend on whoever configures the URL remembering it.
 * {@link #theDriverPropertyIsLoadBearing} keeps that property honest.
 *
 * <p>{@link #thePayloadIsStoredAsAJsonbObjectRatherThanAQuotedString} guards the other
 * failure mode. A mapping that sends the value as JSON rather than as a string can
 * double-encode it, storing {@code "{\"a\":1}"} — a jsonb <em>string</em> containing JSON —
 * which round-trips to Java perfectly and is still useless, because {@code payload->>'x'}
 * returns null. Round-tripping alone would not catch that, so the stored type is asserted
 * from the server's side.
 *
 * <p>The rest of the class covers the two claim queries {@code OutboxRelay} now depends on,
 * which is the first time either has been executed: that a tenant sees only its own entries,
 * that a backed-off entry is not claimed until it is due, that a published one is never
 * claimed again, and that the platform pass sees only the rows with no organization.
 *
 * <p>Everything runs as {@code hatis_app}, the role the application uses, which has no
 * {@code BYPASSRLS}. Rows with no organization are seeded through the migrator connection,
 * which is the container's superuser and therefore outside row level security — that is the
 * only way such a row can be created at all, which is itself the point of
 * {@code V1_016__outbox_platform_publish.sql}.
 *
 * <p>A plain Hibernate {@code SessionFactory} is built directly rather than using
 * {@code @DataJpaTest}, for the reason {@code WebhookEndpointPersistenceIT} gives: the
 * subject under test is the mapping, and reaching it through Spring's test slicing would
 * make a mapping failure indistinguishable from a wiring failure.
 */
@Testcontainers
@DisplayName("Outbox persistence and the relay's claim queries, against PostgreSQL")
class OutboxEntryPersistenceIT {

    private static final String APP_PASSWORD = "integration-test-only";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("hatis")
            .withUsername("hatis_migrator")
            .withPassword("migrator");

    private static EntityManagerFactory entityManagerFactory;

    @BeforeAll
    static void migrateAndBootstrapHibernate() throws Exception {
        PGSimpleDataSource migrator = dataSource(POSTGRES.getUsername(), POSTGRES.getPassword());

        MigrateResult result = Flyway.configure()
                .dataSource(migrator)
                .locations(migrationLocation())
                .cleanDisabled(false)
                .load()
                .migrate();
        if (result.migrationsExecuted == 0) {
            throw new IllegalStateException("Flyway applied no migrations from " + migrationLocation());
        }

        try (Connection connection = migrator.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("alter role hatis_app login password '" + APP_PASSWORD + "'");
        }

        // pgjdbc's stringtype has to reach the driver, and this bootstrap does not go
        // through the Hikari pool where production sets it, so it is passed both ways: as a
        // hibernate.connection.* property, which Hibernate is documented to hand to the
        // driver unprefixed, and on the URL. Putting it on the URL alone was tried first
        // and did not take - the seven writes below all still failed - and which of these
        // two Hibernate honours cannot be settled without running it. Both are harmless
        // together, and theDriverPropertyIsLoadBearing keeps the property from being
        // retired as redundant whichever one turns out to be the operative one.
        String jdbcUrl = POSTGRES.getJdbcUrl();
        Configuration configuration = new Configuration()
                .addAnnotatedClass(OutboxEntry.class)
                .setProperty("jakarta.persistence.jdbc.url",
                        jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "stringtype=unspecified")
                .setProperty("jakarta.persistence.jdbc.user", "hatis_app")
                .setProperty("jakarta.persistence.jdbc.password", APP_PASSWORD)
                .setProperty("jakarta.persistence.jdbc.driver", "org.postgresql.Driver")
                .setProperty("hibernate.connection.stringtype", "unspecified");
        // No hbm2ddl.auto on purpose: the schema belongs to Flyway, and letting Hibernate
        // generate or validate it here would test Hibernate's idea of the schema rather
        // than the one the migrations actually produce.

        entityManagerFactory = configuration.buildSessionFactory();
    }

    @AfterAll
    static void shutdown() {
        if (entityManagerFactory != null) {
            entityManagerFactory.close();
        }
    }

    @Test
    @DisplayName("a jsonb payload survives a write and a read through Hibernate")
    void theJsonbPayloadSurvivesAWriteAndARead() {
        UUID org = UUID.randomUUID();
        String payload = payloadFor("cms.content.published", org);

        UUID id = asTenant(org, em -> {
            OutboxEntry entry = new OutboxEntry(event("cms.content.published", org), payload);
            em.persist(entry);
            return entry.getId();
        });

        OutboxEntry reloaded = asTenant(org, em -> em.find(OutboxEntry.class, id));

        assertThat(reloaded).isNotNull();
        // Compared as documents, not as text. PostgreSQL's jsonb normalises on the way in -
        // it reorders object keys and re-spaces the separators - so the String that comes
        // back is not the String that went in even though it is the same JSON. Asserting
        // text equality here failed for exactly that reason. See
        // theServerNormalisesTheDocumentBeforeStoringIt.
        assertThat(readTree(reloaded.getPayload()))
                .as("if the driver sent this String as character varying, PostgreSQL "
                        + "would have rejected the insert and this line would not be "
                        + "reached at all")
                .isEqualTo(readTree(payload));
        assertThat(reloaded.getEventType()).isEqualTo("cms.content.published");
        assertThat(reloaded.getOrganizationId()).isEqualTo(org);
        assertThat(reloaded.getEventVersion()).isEqualTo(1);
        assertThat(reloaded.getAttempts()).isZero();
        assertThat(reloaded.getPublishedAt()).isNull();
        assertThat(reloaded.getOccurredAt()).isNotNull();
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
        assertThat(reloaded.getVersion()).isNotNull();
    }

    @Test
    @DisplayName("the server normalises the document, so the stored text is not the written text")
    void theServerNormalisesTheDocumentBeforeStoringIt() {
        UUID org = UUID.randomUUID();
        String written = payloadFor("cms.content.published", org);
        UUID id = asTenant(org, em -> {
            OutboxEntry entry = new OutboxEntry(event("cms.content.published", org), written);
            em.persist(entry);
            return entry.getId();
        });

        String stored = asTenant(org, em -> em.find(OutboxEntry.class, id).getPayload());

        // Pinned rather than left as a surprise, because it is load-bearing: jsonb sorts
        // object keys by length and then bytewise and puts a space after each separator,
        // so this text is what the webhook signer signs and what any future payload hash
        // would have to be computed over. Comparing it against the publisher's output
        // will never match. Verified against this schema: the same document written with
        // eventType first comes back with data first.
        assertThat(stored).isNotEqualTo(written);
        assertThat(stored).startsWith("{\"data\": ");
        assertThat(stored)
                .as("sorted by key length, then bytewise: data, eventType, occurredAt, "
                        + "eventVersion, organizationId")
                .contains("}\"eventType\"")
                .doesNotContain("{\"eventType\"");
        assertThat(readTree(stored)).isEqualTo(readTree(written));
    }

    @Test
    @DisplayName("the payload lands in the column as a jsonb object, not a quoted string")
    void thePayloadIsStoredAsAJsonbObjectRatherThanAQuotedString() {
        UUID org = UUID.randomUUID();
        UUID id = asTenant(org, em -> {
            OutboxEntry entry = new OutboxEntry(event("cms.content.published", org),
                    payloadFor("cms.content.published", org));
            em.persist(entry);
            return entry.getId();
        });

        // Asked of the server, not of Hibernate: jsonb_typeof distinguishes an object from
        // a JSON string, which is exactly the distinction a double-encoding mapping hides.
        String storedType = asTenant(org, em -> (String) em
                .createNativeQuery("select jsonb_typeof(payload) from plat_outbox where id = :id")
                .setParameter("id", id)
                .getSingleResult());
        String extractedField = asTenant(org, em -> (String) em
                .createNativeQuery("select payload->>'eventType' from plat_outbox where id = :id")
                .setParameter("id", id)
                .getSingleResult());

        assertThat(storedType)
                .as("a jsonb string would round-trip to Java unchanged and still be wrong")
                .isEqualTo("object");
        assertThat(extractedField).isEqualTo("cms.content.published");
    }

    @Test
    @DisplayName("the claim query returns the tenant's own unpublished entries")
    void theClaimQueryReturnsTheTenantsOwnEntries() {
        UUID org = UUID.randomUUID();
        UUID id = persist(org, "cms.content.published");

        List<UUID> claimed = asTenant(org, em -> repository(em)
                .findPendingIds(org, Instant.now(), PageRequest.of(0, 200)));

        assertThat(claimed).containsExactly(id);
    }

    @Test
    @DisplayName("the claim query returns nothing for a different tenant")
    void theClaimQueryReturnsNothingForAnotherTenant() {
        UUID owner = UUID.randomUUID();
        UUID intruder = UUID.randomUUID();
        persist(owner, "cms.content.published");

        List<UUID> claimed = asTenant(intruder, em -> repository(em)
                .findPendingIds(intruder, Instant.now(), PageRequest.of(0, 200)));

        assertThat(claimed)
                .as("the explicit organizationId predicate and row level security are two "
                        + "layers; either one alone holding is not enough to ship")
                .isEmpty();
    }

    @Test
    @DisplayName("an entry that has backed off is not claimed until it is due")
    void aBackedOffEntryIsNotClaimedUntilItIsDue() {
        UUID org = UUID.randomUUID();
        UUID id = asTenant(org, em -> {
            OutboxEntry entry = new OutboxEntry(event("cms.content.published", org),
                    payloadFor("cms.content.published", org));
            // attempts becomes 1, so the backoff is two seconds out.
            entry.markFailedAttempt();
            em.persist(entry);
            return entry.getId();
        });

        // Queried against an instant a minute in the past rather than now(), so a slow
        // runner cannot turn the two second backoff into a flake. What is under test is the
        // predicate, not the wall clock.
        List<UUID> claimedNow = asTenant(org, em -> repository(em)
                .findPendingIds(org, Instant.now().minusSeconds(60), PageRequest.of(0, 200)));
        List<UUID> claimedLater = asTenant(org, em -> repository(em)
                .findPendingIds(org, Instant.now().plusSeconds(600), PageRequest.of(0, 200)));

        assertThat(claimedNow)
                .as("claiming a backed-off entry immediately would hammer a dead sink on "
                        + "every two second sweep")
                .isEmpty();
        assertThat(claimedLater).containsExactly(id);
    }

    @Test
    @DisplayName("a published entry is never claimed again")
    void aPublishedEntryIsNeverClaimedAgain() {
        UUID org = UUID.randomUUID();
        UUID id = persist(org, "cms.content.published");

        asTenant(org, em -> {
            em.find(OutboxEntry.class, id).markPublished();
            return null;
        });

        List<UUID> claimed = asTenant(org, em -> repository(em)
                .findPendingIds(org, Instant.now().plusSeconds(600), PageRequest.of(0, 200)));

        assertThat(claimed)
                .as("republishing is what the published_at column exists to prevent")
                .isEmpty();
    }

    @Test
    @DisplayName("the platform claim sees the ownerless entries and no tenant's")
    void thePlatformClaimSeesOnlyOwnerlessEntries() throws Exception {
        UUID org = UUID.randomUUID();
        UUID tenantEntryId = persist(org, "cms.content.published");
        UUID platformEventId = UUID.randomUUID();
        // Only a role outside row level security can create a row that belongs to no
        // tenant, which is the invariant V1_016 kept intact.
        insertOwnerlessRowAsMigrator(platformEventId, "platform.maintenance.scheduled");

        List<UUID> claimed = asUnbound(em -> repository(em)
                .findPendingPlatformIds(Instant.now(), PageRequest.of(0, 200)));

        assertThat(claimed)
                .as("with no tenant bound the policy admits only rows whose organization_id "
                        + "is NULL, so a tenant's queue is unreachable from this pass")
                .contains(platformEventId)
                .doesNotContain(tenantEntryId);
    }

    @Test
    @DisplayName("a tenant cannot persist an entry that belongs to no one")
    void aTenantCannotPersistAnEntryThatBelongsToNoOne() {
        UUID org = UUID.randomUUID();

        assertThatThrownBy(() -> asTenant(org, em -> {
            em.persist(new OutboxEntry(event("platform.maintenance.scheduled", null),
                    payloadFor("platform.maintenance.scheduled", null)));
            return null;
        }))
                // WITH CHECK stays tenant strict on insert; only the read and the
                // bookkeeping were widened, in V1_016. Asserted on the root cause, because
                // Hibernate wraps the driver's exception and appends its own SQL.
                .rootCause()
                .hasMessageContaining("row-level security policy for table \"plat_outbox\"");
    }

    @Test
    @DisplayName("the driver property is load-bearing: the same insert is rejected without it")
    void theDriverPropertyIsLoadBearing() throws Exception {
        // A connection with pgjdbc's stringtype left at its varchar default, which is the
        // shape of a deployment that never set the property. Run as the migrator, which is
        // the container's superuser, so row level security cannot be what fails and the
        // cause of the rejection is unambiguous.
        try (Connection connection = dataSource(POSTGRES.getUsername(), POSTGRES.getPassword())
                .getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "insert into plat_outbox (id, aggregate_type, event_type,"
                             + " organization_id, occurred_at, payload)"
                             + " values (?, 'platform', ?, null, now(), ?)")) {
            statement.setObject(1, UUID.randomUUID());
            statement.setString(2, "cms.content.published");
            statement.setString(3, payloadFor("cms.content.published", null));

            assertThatThrownBy(statement::execute)
                    .as("this is the failure the stringtype property exists to prevent; if "
                            + "it ever stops failing, the property can be retired")
                    .hasMessageContaining("column \"payload\" is of type jsonb"
                            + " but expression is of type character varying");
        }
    }

    private static UUID persist(UUID organizationId, String eventType) {
        return asTenant(organizationId, em -> {
            OutboxEntry entry = new OutboxEntry(event(eventType, organizationId),
                    payloadFor(eventType, organizationId));
            em.persist(entry);
            return entry.getId();
        });
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode readTree(String json) {
        try {
            return JSON.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("not JSON: " + json, e);
        }
    }

    private static OutboxRepository repository(EntityManager em) {
        return new JpaRepositoryFactory(em).getRepository(OutboxRepository.class);
    }

    private static PlatformEvent event(String eventType, UUID organizationId) {
        return PlatformEvent.of(eventType, organizationId)
                .resource("content_item", UUID.randomUUID())
                .correlationId("corr-" + eventType)
                .build();
    }

    /** The envelope shape documented on {@link PlatformEvent}, written out by hand. */
    private static String payloadFor(String eventType, UUID organizationId) {
        return "{\"eventType\":\"" + eventType + "\","
                + "\"eventVersion\":1,"
                + "\"organizationId\":" + (organizationId == null ? "null" : "\"" + organizationId + "\"") + ","
                + "\"occurredAt\":\"2026-09-19T10:00:00Z\","
                + "\"data\":{\"itemId\":\"abc\"}}";
    }

    private static void insertOwnerlessRowAsMigrator(UUID eventId, String eventType) throws SQLException {
        try (Connection connection = dataSource(POSTGRES.getUsername(), POSTGRES.getPassword())
                .getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "insert into plat_outbox (id, aggregate_type, event_type, organization_id,"
                             + " occurred_at, payload) values (?, 'platform', ?, null, now(),"
                             + " cast(? as jsonb))")) {
            statement.setObject(1, eventId);
            statement.setString(2, eventType);
            statement.setString(3, payloadFor(eventType, null));
            statement.execute();
        }
    }

    /**
     * Runs work as one tenant.
     *
     * <p>{@code set_config} is given {@code true} for the transaction-local flag, so the
     * setting dies with the transaction instead of leaking to the next connection pooled
     * behind this one. Forgetting that flag is how a tenant ends up reading another
     * tenant's rows hours later, from a connection that still carries the old value.
     */
    private static <T> T asTenant(UUID organizationId, Function<EntityManager, T> work) {
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            em.getTransaction().begin();
            em.createNativeQuery("select set_config('hatis.organization_id', :org, true)")
                    .setParameter("org", organizationId.toString())
                    .getSingleResult();
            T result = work.apply(em);
            em.flush();
            em.getTransaction().commit();
            return result;
        } catch (RuntimeException e) {
            if (em.getTransaction().isActive()) {
                em.getTransaction().rollback();
            }
            throw e;
        } finally {
            em.close();
        }
    }

    /**
     * Runs work with no tenant bound, which is how {@code OutboxRelay} takes its
     * platform-wide pass. The setting is explicitly cleared rather than merely never set,
     * because a pooled connection can arrive already carrying somebody's tenant.
     */
    private static <T> T asUnbound(Function<EntityManager, T> work) {
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            em.getTransaction().begin();
            em.createNativeQuery("select set_config('hatis.organization_id', null::text, true)")
                    .getSingleResult();
            T result = work.apply(em);
            em.flush();
            em.getTransaction().commit();
            return result;
        } catch (RuntimeException e) {
            if (em.getTransaction().isActive()) {
                em.getTransaction().rollback();
            }
            throw e;
        } finally {
            em.close();
        }
    }

    private static PGSimpleDataSource dataSource(String user, String password) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(user);
        dataSource.setPassword(password);
        return dataSource;
    }

    /**
     * The migrations are read from the module source tree, for the same reason
     * {@code TenantIsolationIT} does it: Flyway's classpath scanner resolves nothing under
     * the runner's classloader and then reports success having applied no migrations. Both
     * the module directory and the repository root are tried, because the working directory
     * depends on how the runner was invoked.
     */
    private static String migrationLocation() {
        for (String candidate : new String[]{
                "src/main/resources/db/migration",
                "backend/hatis-api/src/main/resources/db/migration"}) {
            java.nio.file.Path path = java.nio.file.Path.of(candidate);
            if (java.nio.file.Files.isDirectory(path)) {
                return "filesystem:" + path.toAbsolutePath();
            }
        }
        return "classpath:db/migration";
    }
}
