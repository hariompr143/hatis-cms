package com.hatis.platform.persistence;

import com.hatis.platform.audit.domain.AuditLog;
import com.hatis.platform.cms.domain.ContentType;
import com.hatis.platform.cms.domain.ContentVersion;
import com.hatis.platform.integration.domain.InboundIntegration;
import com.hatis.platform.shared.audit.AuditRecord;
import com.hatis.platform.shared.id.Identifiers;
import com.hatis.platform.shared.idempotency.IdempotencyRecord;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The five {@code String}-to-{@code jsonb} mappings that nothing had ever written, written.
 *
 * <h2>Why this exists</h2>
 *
 * Six entities map a Java {@code String} onto a PostgreSQL {@code jsonb} column. One of them
 * — {@code OutboxEntry.payload} — is covered by {@code OutboxEntryPersistenceIT}, which is
 * where the {@code stringtype} defect was found and fixed. The other five had never had a row
 * flushed through Hibernate in any form: no audit record, no content type, no content
 * version, no inbound integration configuration, no idempotency response.
 *
 * <p>Sharing a mapping shape with the sixth is not the same as being covered by its test.
 * The original defect was invisible in exactly that way — the annotations on all six were
 * correct and all six would have failed at the first write, because the fault was in the
 * driver rather than in any one mapping.
 *
 * <h2>What is asserted, and from which side</h2>
 *
 * Each test writes a document and then asks <em>PostgreSQL</em> what it stored, rather than
 * asking Hibernate for the value back. That is deliberate. A mapping that sends the string as
 * a JSON scalar double-encodes it: the column holds {@code "{\"a\":1}"} — a jsonb
 * <em>string</em> containing JSON — which round-trips to Java perfectly and is still useless,
 * because {@code column->>'a'} returns null. Reading the value back through the same mapping
 * that wrote it cannot see that. So the type and a path extraction are both read from the
 * server.
 *
 * <p>What is <em>not</em> asserted is string equality with what was written. PostgreSQL
 * normalises {@code jsonb} on the way in, sorting object keys by length and then bytewise and
 * re-spacing the separators, so the stored text is never the published text. Comparing them
 * would fail for a reason that has nothing to do with the mapping.
 */
@Testcontainers
@DisplayName("The remaining String-to-jsonb mappings, written against PostgreSQL")
class JsonbEntityRoundTripIT {

    private static final String APP_PASSWORD = "integration-test-only";

    /** 64 hex characters, which is what the {@code char(64)} audit columns require. */
    private static final String HASH = "0".repeat(64);

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
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute("alter role hatis_app login password '" + APP_PASSWORD + "'");
        }

        // stringtype reaches the driver here through hibernate.connection.stringtype,
        // because this bootstrap does not go through the Hikari pool where production sets
        // it. JsonbDataSourceConfigurationIT covers the pool path.
        Configuration configuration = new Configuration()
                .addAnnotatedClass(AuditLog.class)
                .addAnnotatedClass(ContentType.class)
                .addAnnotatedClass(ContentVersion.class)
                .addAnnotatedClass(InboundIntegration.class)
                .addAnnotatedClass(IdempotencyRecord.class)
                .setProperty("jakarta.persistence.jdbc.url", POSTGRES.getJdbcUrl())
                .setProperty("jakarta.persistence.jdbc.user", "hatis_app")
                .setProperty("jakarta.persistence.jdbc.password", APP_PASSWORD)
                .setProperty("jakarta.persistence.jdbc.driver", "org.postgresql.Driver")
                .setProperty("hibernate.connection.stringtype", "unspecified");
        entityManagerFactory = configuration.buildSessionFactory();
    }

    @AfterAll
    static void shutdown() {
        if (entityManagerFactory != null) {
            entityManagerFactory.close();
        }
    }

    @Test
    @DisplayName("AuditLog.metadata is stored as a queryable jsonb object")
    void auditLogMetadataIsStoredAsAJsonbObject() throws Exception {
        UUID organizationId = UUID.randomUUID();
        UUID id = Identifiers.newId();

        asTenant(organizationId, em -> {
            em.persist(new AuditLog(id, organizationId, AuditRecord.ActorType.USER,
                    Identifiers.newId(), "editor@example.invalid", "content.published",
                    "content_item", Identifiers.newId(), AuditRecord.Result.SUCCESS, null,
                    "203.0.113.7", "test-agent", null,
                    "{\"action\":\"content.published\",\"detail\":{\"version\":3}}",
                    HASH, HASH, 1L, Instant.now()));
            return null;
        });

        assertStoredAsJsonObject("aud_audit_logs", "metadata", id, "action", "content.published");
    }

    @Test
    @DisplayName("ContentType.schema is stored as a queryable jsonb object")
    void contentTypeSchemaIsStoredAsAJsonbObject() throws Exception {
        UUID organizationId = UUID.randomUUID();
        UUID id = asTenant(organizationId, em -> {
            ContentType type = new ContentType(organizationId, Identifiers.newId(), "Article",
                    "article", "Long-form content",
                    "{\"fields\":[{\"name\":\"headline\",\"type\":\"string\"}]}",
                    "headline", Identifiers.newId());
            em.persist(type);
            return type.getId();
        });

        assertStoredAsJsonObject("cms_content_types", "schema", id, "fields",
                // jsonb re-spaces separators, so the extracted value is the server's text.
                "[{\"name\": \"headline\", \"type\": \"string\"}]");
    }

    @Test
    @DisplayName("ContentVersion.body is stored as a queryable jsonb object")
    void contentVersionBodyIsStoredAsAJsonbObject() throws Exception {
        UUID organizationId = UUID.randomUUID();
        UUID id = asTenant(organizationId, em -> {
            ContentVersion version = new ContentVersion(organizationId, Identifiers.newId(), 1,
                    "{\"blocks\":[{\"type\":\"text\",\"value\":\"hello\"}]}",
                    "hello", "first version", Identifiers.newId());
            em.persist(version);
            return version.getId();
        });

        assertStoredAsJsonObject("cms_content_versions", "body", id, "blocks",
                "[{\"type\": \"text\", \"value\": \"hello\"}]");
    }

    @Test
    @DisplayName("InboundIntegration.config is stored as a queryable jsonb object")
    void inboundIntegrationConfigIsStoredAsAJsonbObject() throws Exception {
        UUID organizationId = UUID.randomUUID();
        UUID id = asTenant(organizationId, em -> {
            InboundIntegration integration = new InboundIntegration(organizationId,
                    InboundIntegration.Type.GENERIC_WEBHOOK, "Deploy hook",
                    "{\"topics\":[\"push\"],\"verifySignature\":true}",
                    "hatis/integrations/credential");
            em.persist(integration);
            return integration.getId();
        });

        assertStoredAsJsonObject("int_integrations", "config", id, "topics", "[\"push\"]");
    }

    @Test
    @DisplayName("IdempotencyRecord.responseBody is stored as a queryable jsonb object")
    void idempotencyResponseBodyIsStoredAsAJsonbObject() throws Exception {
        UUID organizationId = UUID.randomUUID();
        UUID id = asTenant(organizationId, em -> {
            IdempotencyRecord record = new IdempotencyRecord(organizationId,
                    UUID.randomUUID().toString(), "POST", "/v1/content",
                    "9".repeat(64), Instant.now().plus(24, ChronoUnit.HOURS));
            record.complete(201, "{\"id\":\"abc\",\"created\":true}");
            em.persist(record);
            return record.getId();
        });

        assertStoredAsJsonObject("plat_idempotency_keys", "response_body", id, "id", "abc");
    }

    /**
     * Asks PostgreSQL what it actually stored: the jsonb type, and a value extracted by
     * path. A double-encoded write shows up here as {@code string} with a null extraction
     * while round-tripping to Java without complaint.
     *
     * <p>Read through the migrator, which is outside row level security, so the assertion is
     * about storage and cannot be confused with a policy that happens to hide the row.
     */
    private static void assertStoredAsJsonObject(String table, String column, UUID id,
                                                 String key, String expected) throws SQLException {
        // Table and column names are literals from this class, never input.
        try (Connection connection =
                     dataSource(POSTGRES.getUsername(), POSTGRES.getPassword()).getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select jsonb_typeof(" + column + "), " + column + " ->> ?"
                             + " from " + table + " where id = ?")) {
            statement.setString(1, key);
            statement.setObject(2, id);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next())
                        .as("the %s row written above must exist", table)
                        .isTrue();
                assertThat(rows.getString(1))
                        .as("%s.%s must hold a jsonb object; a quoted string would mean the "
                                + "document was double-encoded on the way in", table, column)
                        .isEqualTo("object");
                assertThat(rows.getString(2))
                        .as("%s.%s must be queryable by path, which is the only reason it is "
                                + "jsonb rather than text", table, column)
                        .isEqualTo(expected);
            }
        }
    }

    /**
     * Runs work inside a transaction with the tenant bound, which is what the forced row
     * level security on these tables requires: {@code set_config} is transaction-local, so
     * the binding has to be made in the same transaction as the write.
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
     * the runner's classloader and then reports success having applied no migrations.
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
