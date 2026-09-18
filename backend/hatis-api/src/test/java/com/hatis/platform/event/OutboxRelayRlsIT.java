package com.hatis.platform.event;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
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
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What row level security actually does to the transactional outbox.
 *
 * <h2>These tests characterise a defect, and two of them are meant to read as alarming</h2>
 *
 * {@code plat_outbox} is in the strict tenant table list, so it carries
 * {@code force row level security} with a policy of
 * {@code organization_id = nullif(current_setting('hatis.organization_id', true), '')::uuid}.
 * The migrations also strip {@code BYPASSRLS} from {@code hatis_app} if anything ever
 * grants it. Put those two facts together and:
 *
 * <ul>
 *   <li>{@code OutboxRelay.drainBatch()} calls {@code outbox.findPending(...)} with no
 *       tenant context bound, so the policy compares against NULL and matches nothing.
 *       <strong>The relay cannot see a single row, and no event is ever published.</strong>
 *       That is {@link #theRelaySeesNothingWithoutATenantContext}.</li>
 *   <li>An event with no organization — {@code organization_id} is nullable and platform
 *       events use it — is invisible under <em>every</em> tenant's context, so no
 *       tenant-scoped read can ever publish one. That is
 *       {@link #aPlatformWideRowIsInvisibleToEveryTenant}.</li>
 * </ul>
 *
 * <p>Both were reproduced against a plain PostgreSQL 16.2 before this test was written, and
 * this file exists to keep them reproducible in CI instead of living only in a paragraph of
 * documentation. The assertions state what the code does today, not what it should do. When
 * the relay is fixed, these two tests must be rewritten to assert the fixed behaviour, and
 * the fact that they have to change is the point.
 *
 * <p>The fix is a decision about the security model rather than a one-line change: either a
 * dedicated worker role that does bypass row level security, or a relay that iterates
 * tenants and binds each one in turn. Platform events need a third thing either way — a
 * read policy that admits rows with no organization, the way {@code auth_roles} and
 * {@code wf_definitions} already do for catalogue rows.
 */
@Testcontainers
@DisplayName("Row level security and the transactional outbox")
class OutboxRelayRlsIT {

    private static final String APP_PASSWORD = "integration-test-only";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("hatis")
            .withUsername("hatis_migrator")
            .withPassword("migrator");

    private static PGSimpleDataSource migratorDataSource;
    private static PGSimpleDataSource appDataSource;

    @BeforeAll
    static void migrate() throws Exception {
        migratorDataSource = dataSource(POSTGRES.getUsername(), POSTGRES.getPassword());

        MigrateResult result = Flyway.configure()
                .dataSource(migratorDataSource)
                .locations(migrationLocation())
                .cleanDisabled(false)
                .load()
                .migrate();
        if (result.migrationsExecuted == 0) {
            throw new IllegalStateException("Flyway applied no migrations from " + migrationLocation());
        }
        try (Connection connection = migratorDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("alter role hatis_app login password '" + APP_PASSWORD + "'");
        }
        appDataSource = dataSource("hatis_app", APP_PASSWORD);
    }

    @Test
    @DisplayName("a tenant transaction can write and read its own outbox row")
    void aTenantTransactionCanPublishAndReadBack() throws Exception {
        UUID organizationId = UUID.randomUUID();

        try (Connection connection = appDataSource.getConnection()) {
            bindTenant(connection, organizationId);
            insertOutboxRow(connection, organizationId, "content.published");
            assertThat(pendingCount(connection))
                    .as("the publisher runs inside a tenant transaction, so RLS lets it through")
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("DEFECT: the relay, which binds no tenant, sees no outbox rows at all")
    void theRelaySeesNothingWithoutATenantContext() throws Exception {
        UUID organizationId = UUID.randomUUID();
        try (Connection connection = appDataSource.getConnection()) {
            bindTenant(connection, organizationId);
            insertOutboxRow(connection, organizationId, "content.published");
            // bindTenant leaves autocommit off, so without this the row would be rolled
            // back on close and the next assertion would pass because the outbox was
            // empty rather than because the relay cannot see it.
            connection.commit();
        }

        // Exactly what OutboxRelay.drainBatch() does: a plain query on a fresh connection
        // with no hatis.organization_id bound, because nothing in the relay binds one.
        try (Connection connection = appDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            assertThat(connection.getAutoCommit())
                    .as("no transaction, therefore no transaction-local tenant setting")
                    .isTrue();
            assertThat(pendingCount(statement))
                    .as("the outbox is not empty, yet an unbound relay sees none of it, "
                            + "so no event is ever published")
                    .isZero();
        }
    }

    @Test
    @DisplayName("DEFECT: a platform-wide event is invisible to every tenant")
    void aPlatformWideRowIsInvisibleToEveryTenant() throws Exception {
        UUID organizationId = UUID.randomUUID();
        // Written as the migrator, which is not subject to RLS, standing in for any
        // platform-level producer.
        try (Connection connection = migratorDataSource.getConnection()) {
            insertOutboxRow(connection, null, "platform.maintenance.scheduled");
        }

        try (Connection connection = appDataSource.getConnection()) {
            bindTenant(connection, organizationId);
            assertThat(countNullOrganizationRows(connection))
                    .as("organization_id IS NULL can never equal a tenant's id, so no "
                            + "tenant-scoped read can publish a platform event")
                    .isZero();
        }
    }

    @Test
    @DisplayName("DEFECT: the organization table is tenant scoped too, so no tenant list is readable")
    void theOrganizationTableIsNotAReadableTenantDirectory() throws Exception {
        UUID organizationId = UUID.randomUUID();
        try (Connection connection = migratorDataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "insert into org_organizations (id, organization_id, name, slug)"
                             + " values (?, ?, ?, ?)")) {
            statement.setObject(1, organizationId);
            statement.setObject(2, organizationId);
            statement.setString(3, "Directory Tenant");
            statement.setString(4, "directory-" + organizationId);
            statement.execute();
        }

        try (Connection connection = appDataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select count(*) from org_organizations")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1))
                    .as("org_organizations is in the strict tenant list and carries "
                            + "check (id = organization_id), so a background job with no "
                            + "tenant bound cannot enumerate tenants at all. Any fix that "
                            + "iterates organizations needs a source that is not itself "
                            + "row level scoped.")
                    .isZero();
        }
    }

    @Test
    @DisplayName("the policy is the reason, not a missing grant")
    void theBlockComesFromThePolicyRatherThanPermissions() throws Exception {
        try (Connection connection = appDataSource.getConnection()) {
            // hatis_app holds table-level SELECT; what stops it is the row policy. If this
            // ever returns false, the diagnosis in this file is wrong and so is the fix.
            assertThat(hasTablePrivilege(connection, "hatis_app", "plat_outbox", "SELECT")).isTrue();
            assertThat(roleBypassesRls(connection, "hatis_app"))
                    .as("the migrations explicitly strip BYPASSRLS from hatis_app")
                    .isFalse();
        }
    }

    private static int pendingCount(Statement statement) throws SQLException {
        try (ResultSet rows = statement.executeQuery(
                "select count(*) from plat_outbox where published_at is null"
                        + " and (next_attempt_at is null or next_attempt_at <= now())")) {
            assertThat(rows.next()).isTrue();
            return rows.getInt(1);
        }
    }

    private static int pendingCount(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            return pendingCount(statement);
        }
    }

    private static int countNullOrganizationRows(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "select count(*) from plat_outbox where organization_id is null")) {
            assertThat(rows.next()).isTrue();
            return rows.getInt(1);
        }
    }

    private static boolean hasTablePrivilege(Connection connection, String role, String table,
                                             String privilege) throws SQLException {
        try (PreparedStatement prepared = connection.prepareStatement(
                "select has_table_privilege(?, ?, ?)")) {
            prepared.setString(1, role);
            prepared.setString(2, table);
            prepared.setString(3, privilege);
            try (ResultSet rows = prepared.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getBoolean(1);
            }
        }
    }

    private static boolean roleBypassesRls(Connection connection, String role) throws SQLException {
        try (PreparedStatement prepared = connection.prepareStatement(
                "select rolbypassrls from pg_roles where rolname = ?")) {
            prepared.setString(1, role);
            try (ResultSet rows = prepared.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getBoolean(1);
            }
        }
    }

    private static void insertOutboxRow(Connection connection, UUID organizationId,
                                        String eventType) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into plat_outbox (id, aggregate_type, event_type, organization_id,"
                        + " occurred_at, payload) values (?, 'content_item', ?, ?, now(),"
                        + " cast(? as jsonb))")) {
            statement.setObject(1, UUID.randomUUID());
            statement.setString(2, eventType);
            if (organizationId == null) {
                // A platform-wide event. Typed explicitly, because setObject(i, null)
                // leaves the driver to guess a type for a uuid column.
                statement.setNull(3, java.sql.Types.OTHER);
            } else {
                statement.setObject(3, organizationId);
            }
            statement.setString(4, "{\"eventType\":\"" + eventType + "\"}");
            statement.execute();
        }
    }

    private static void bindTenant(Connection connection, UUID organizationId) throws SQLException {
        // set_config with is_local = true is transaction scoped, so the connection has to
        // leave autocommit or the setting is gone before the next statement runs.
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement("select set_config(?, ?, true)")) {
            statement.setString(1, "hatis.organization_id");
            statement.setString(2, organizationId.toString());
            statement.execute();
        }
    }

    private static PGSimpleDataSource dataSource(String user, String password) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(POSTGRES.getJdbcUrl());
        source.setUser(user);
        source.setPassword(password);
        return source;
    }

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
