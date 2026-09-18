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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What row level security does to the transactional outbox, and what {@code V1_015}
 * changed about it.
 *
 * <p>These tests exist because the behaviour here is not obvious from the code and twice
 * turned out to be the opposite of what a comment claimed. Assertions are scoped to a
 * specific row id rather than counting the table, because the container is shared between
 * tests and a count would make one test's result depend on another's ordering.
 *
 * <h2>What V1_015 fixed</h2>
 *
 * <ul>
 *   <li>Platform-wide events — {@code organization_id} is NULL — were invisible under
 *       every tenant, because NULL can never equal a tenant's id. {@code plat_outbox} now
 *       carries a widened read policy of the kind {@code auth_roles} already had, while
 *       {@code WITH CHECK} stays strict so a tenant still cannot create an event claiming
 *       to belong to nobody.</li>
 *   <li>Background jobs had nowhere to get a list of tenants: {@code org_organizations} is
 *       itself tenant scoped. {@code plat_tenant_directory} holds nothing but ids and has
 *       no row level security, so a job can enumerate tenants and then bind each one.</li>
 * </ul>
 *
 * <h2>What V1_016 then had to fix</h2>
 *
 * A widened {@code USING} with a strict {@code WITH CHECK} is worse than either on its own:
 * the relay could <em>read</em> a platform-wide row but not record that it had published it,
 * because {@code WITH CHECK} is evaluated against the new row version on every update and
 * {@code organization_id = <tenant>} is never true for a NULL. The row would be republished
 * on every sweep, forever. {@code V1_016} splits the single {@code FOR ALL} policy into one
 * policy per command: reads and delivery bookkeeping admit ownerless rows, inserts do not.
 * {@link #aPlatformWideRowCanBeMarkedPublishedUnbound} and
 * {@link #aTenantStillCannotInsertAnEventThatBelongsToNoOne} hold the two halves apart.
 *
 * <h2>What is still true, and constrains the relay</h2>
 *
 * A query with no tenant bound still sees no tenant-owned rows. That is correct and must
 * stay correct; it is why {@code OutboxRelay} walks {@code plat_tenant_directory} and
 * binds each tenant in turn rather than asking for the whole queue at once, and why the
 * "everything pending, whichever tenant it belongs to" query on {@code OutboxRepository}
 * was deleted instead of being left unused — an unused query that silently returns nothing
 * is how the relay came to publish no events at all.
 * {@link #anUnboundQueryStillSeesNoTenantOwnedRows} is the test that would fail if anyone
 * "fixed" a future relay by weakening this instead.
 */
@Testcontainers
@DisplayName("Row level security, the transactional outbox and the tenant directory")
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
    @DisplayName("a tenant transaction can publish and read back its own outbox row")
    void aTenantTransactionCanPublishAndReadBack() throws Exception {
        UUID organizationId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        try (Connection connection = appDataSource.getConnection()) {
            bindTenant(connection, organizationId);
            insertOutboxRow(connection, eventId, organizationId, "content.published");
            assertThat(countById(connection, eventId))
                    .as("the publisher runs inside a tenant transaction, so RLS lets it through")
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("an unbound query still sees no tenant-owned rows")
    void anUnboundQueryStillSeesNoTenantOwnedRows() throws Exception {
        UUID organizationId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        try (Connection connection = appDataSource.getConnection()) {
            bindTenant(connection, organizationId);
            insertOutboxRow(connection, eventId, organizationId, "content.published");
            // bindTenant leaves autocommit off, so without this the row would be rolled
            // back and the next assertion would pass because the table was empty.
            connection.commit();
        }

        try (Connection connection = appDataSource.getConnection()) {
            assertThat(connection.getAutoCommit())
                    .as("no transaction, therefore no transaction-local tenant setting")
                    .isTrue();
            assertThat(countById(connection, eventId))
                    .as("this must stay zero: the relay has to bind a tenant per sweep, and "
                            + "weakening this policy would be the wrong way to fix it")
                    .isZero();
        }
    }

    @Test
    @DisplayName("a platform-wide event is now readable by any tenant")
    void aPlatformWideRowIsReadableByAnyTenant() throws Exception {
        UUID organizationId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        try (Connection connection = migratorDataSource.getConnection()) {
            insertOutboxRow(connection, eventId, null, "platform.maintenance.scheduled");
        }

        try (Connection connection = appDataSource.getConnection()) {
            bindTenant(connection, organizationId);
            assertThat(countById(connection, eventId))
                    .as("V1_015 widened reads so a relay can publish events that belong to "
                            + "no tenant; before it this was always zero")
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("widened reads do not widen writes: a tenant cannot create an ownerless event")
    void aTenantCannotInsertAnEventThatBelongsToNoOne() throws Exception {
        UUID organizationId = UUID.randomUUID();

        try (Connection connection = appDataSource.getConnection()) {
            bindTenant(connection, organizationId);
            // USING admits NULL-organization rows, WITH CHECK does not. If this ever
            // succeeds, a tenant can publish events that look platform-issued.
            assertThatThrownBy(() -> insertOutboxRow(connection, UUID.randomUUID(), null,
                    "platform.maintenance.scheduled"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("row-level security");
        }
    }

    @Test
    @DisplayName("the organization table is still tenant scoped")
    void theOrganizationTableIsStillTenantScoped() throws Exception {
        UUID organizationId = insertOrganization("still-scoped");

        try (Connection connection = appDataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "select count(*) from org_organizations where id = '" + organizationId + "'")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1))
                    .as("org_organizations is in the strict tenant list with "
                            + "check (id = organization_id); V1_015 deliberately did not "
                            + "widen it, because its rows carry encryption_key_wrapped")
                    .isZero();
        }
    }

    @Test
    @DisplayName("the tenant directory is readable with no tenant bound")
    void theTenantDirectoryIsReadableWithoutATenantBound() throws Exception {
        UUID organizationId = insertOrganization("directory-tenant");

        try (Connection connection = appDataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select count(*) from plat_tenant_directory where organization_id = ?")) {
            statement.setObject(1, organizationId);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1))
                        .as("the directory is what lets a background job enumerate tenants "
                                + "and then bind each one; the trigger on org_organizations "
                                + "put this row there")
                        .isEqualTo(1);
            }
        }
    }

    @Test
    @DisplayName("the directory holds ids only, not the organization's key material")
    void theDirectoryCarriesNoKeyMaterial() throws Exception {
        try (Connection connection = appDataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "select column_name from information_schema.columns"
                             + " where table_name = 'plat_tenant_directory' order by column_name")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("created_at");
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("organization_id");
            assertThat(rows.next())
                    .as("a widened read on org_organizations would have exposed "
                            + "encryption_key_wrapped; a directory of ids does not")
                    .isFalse();
        }
    }

    @Test
    @DisplayName("a platform-wide row can be marked published by a relay with no tenant bound")
    void aPlatformWideRowCanBeMarkedPublishedUnbound() throws Exception {
        UUID eventId = UUID.randomUUID();
        try (Connection connection = migratorDataSource.getConnection()) {
            insertOutboxRow(connection, eventId, null, "platform.maintenance.scheduled");
        }

        try (Connection connection = appDataSource.getConnection()) {
            assertThat(connection.getAutoCommit())
                    .as("no tenant bound, which is the state the relay runs the platform "
                            + "pass in")
                    .isTrue();
            assertThat(updatePublishedAt(connection, eventId))
                    .as("V1_016 lets the update through; before it, WITH CHECK rejected the "
                            + "new row version and the entry would be republished forever")
                    .isEqualTo(1);
            assertThat(publishedAtIsNull(connection, eventId)).isFalse();
        }
    }

    @Test
    @DisplayName("an unbound relay still cannot touch a tenant's row")
    void anUnboundRelayCannotReachATenantsRow() throws Exception {
        UUID organizationId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        try (Connection connection = appDataSource.getConnection()) {
            bindTenant(connection, organizationId);
            insertOutboxRow(connection, eventId, organizationId, "content.published");
            connection.commit();
        }

        try (Connection connection = appDataSource.getConnection()) {
            assertThat(updatePublishedAt(connection, eventId))
                    .as("widening the write path for ownerless rows must not widen it for "
                            + "rows that have an owner")
                    .isZero();
            assertThat(countById(connection, eventId))
                    .as("and it must not widen reads either: the row is not merely "
                            + "unwritable from here, it is invisible")
                    .isZero();
        }
    }

    @Test
    @DisplayName("one tenant cannot mark another tenant's entry published")
    void oneTenantCannotPublishAnotherTenantsEntry() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID intruder = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        try (Connection connection = appDataSource.getConnection()) {
            bindTenant(connection, owner);
            insertOutboxRow(connection, eventId, owner, "content.published");
            connection.commit();
        }

        try (Connection connection = appDataSource.getConnection()) {
            bindTenant(connection, intruder);
            assertThat(updatePublishedAt(connection, eventId))
                    .as("an update that matches no visible row affects zero rows; RLS does "
                            + "not raise, it filters")
                    .isZero();
        }
    }

    @Test
    @DisplayName("the block on tenant rows is the policy, not a missing grant")
    void theBlockComesFromThePolicyRatherThanPermissions() throws Exception {
        try (Connection connection = appDataSource.getConnection()) {
            assertThat(hasTablePrivilege(connection, "hatis_app", "plat_outbox", "SELECT")).isTrue();
            assertThat(roleBypassesRls(connection, "hatis_app"))
                    .as("the migrations explicitly strip BYPASSRLS from hatis_app")
                    .isFalse();
        }
    }

    private static UUID insertOrganization(String slug) throws SQLException {
        UUID organizationId = UUID.randomUUID();
        try (Connection connection = migratorDataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "insert into org_organizations (id, organization_id, name, slug)"
                             + " values (?, ?, ?, ?)")) {
            statement.setObject(1, organizationId);
            statement.setObject(2, organizationId);
            statement.setString(3, slug);
            statement.setString(4, slug + "-" + organizationId);
            statement.execute();
        }
        return organizationId;
    }

    private static int countById(Connection connection, UUID eventId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select count(*) from plat_outbox where id = ?")) {
            statement.setObject(1, eventId);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getInt(1);
            }
        }
    }

    private static int updatePublishedAt(Connection connection, UUID eventId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "update plat_outbox set published_at = now() where id = ?")) {
            statement.setObject(1, eventId);
            return statement.executeUpdate();
        }
    }

    /**
     * Reads back through the caller's own policy, so for a row the caller cannot see this
     * reports "unpublished" rather than the owner's value. Every caller of this method
     * states which of the two it means.
     */
    private static boolean publishedAtIsNull(Connection connection, UUID eventId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select count(*) from plat_outbox where id = ? and published_at is null")) {
            statement.setObject(1, eventId);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getInt(1) == 1;
            }
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

    private static void insertOutboxRow(Connection connection, UUID eventId, UUID organizationId,
                                        String eventType) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into plat_outbox (id, aggregate_type, event_type, organization_id,"
                        + " occurred_at, payload) values (?, 'content_item', ?, ?, now(),"
                        + " cast(? as jsonb))")) {
            statement.setObject(1, eventId);
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
