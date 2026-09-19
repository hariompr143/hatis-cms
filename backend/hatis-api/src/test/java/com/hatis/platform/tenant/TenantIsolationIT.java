package com.hatis.platform.tenant;

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
 * Tenant isolation, tested against a real PostgreSQL instance.
 *
 * <p>This is the most important test in the repository. The platform's isolation
 * model has two layers — an explicit {@code organization_id} predicate in every
 * query, and row level security as the backstop — and only the second one can be
 * tested honestly here, because it is the layer that still holds when a developer
 * forgets the first.
 *
 * <p>The tests run as {@code hatis_app}, the role the application actually uses,
 * which has no {@code BYPASSRLS}. A test that ran as the superuser would pass while
 * the production configuration leaked.
 */
@Testcontainers
@DisplayName("Tenant isolation enforced by row level security")
class TenantIsolationIT {

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

        org.flywaydb.core.api.output.MigrateResult result = org.flywaydb.core.Flyway.configure()
                .dataSource(migratorDataSource)
                .locations(migrationLocation())
                .cleanDisabled(false)
                .load()
                .migrate();

        // A migration run that applies nothing succeeds quietly, and every later
        // assertion then fails somewhere far from the cause. Say so here instead.
        if (result.migrationsExecuted == 0) {
            throw new IllegalStateException("Flyway applied no migrations from "
                    + migrationLocation());
        }

        // The migration creates hatis_app without a password so that an operator
        // supplies one. Tests set a throwaway value; nothing here is a credential.
        try (Connection connection = migratorDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("alter role hatis_app login password '" + APP_PASSWORD + "'");
        }
        appDataSource = dataSource("hatis_app", APP_PASSWORD);
    }

    /**
     * Where the migration scripts are read from.
     *
     * <p>The scripts are read from the module's source tree, because Flyway's
     * classpath scanner resolves nothing under the runner's classloader and then
     * reports success having applied no migrations. Reading the files directly is
     * deterministic, and it is the same set of files the application packages -
     * validating them is the whole point of this test. The classpath location the
     * application itself uses is kept as a fallback for a checkout laid out
     * differently. The relative path depends on the working directory given to the
     * runner, so both the module directory and the repository root are tried.
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

    private static PGSimpleDataSource dataSource(String user, String password) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(POSTGRES.getJdbcUrl());
        source.setUser(user);
        source.setPassword(password);
        return source;
    }

    @Test
    @DisplayName("a transaction with no tenant bound sees no tenant rows at all")
    void unboundTransactionSeesNothing() throws Exception {
        UUID organizationId = createOrganization("unbound-tenant");
        insertProject(organizationId, "unbound-project");

        try (Connection connection = appDataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select count(*) from org_projects")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1))
                    .as("an unbound transaction must fail closed, not see every tenant's rows")
                    .isZero();
        }
    }

    @Test
    @DisplayName("a tenant sees its own rows and no others")
    void tenantSeesOnlyItsOwnRows() throws Exception {
        UUID tenantA = createOrganization("tenant-a");
        UUID tenantB = createOrganization("tenant-b");
        insertProject(tenantA, "project-a");
        insertProject(tenantB, "project-b");

        assertThat(countProjectsAs(tenantA)).isEqualTo(1);
        assertThat(countProjectsAs(tenantB)).isEqualTo(1);
    }

    @Test
    @DisplayName("a tenant cannot read another tenant's row by id")
    void cannotReadForeignKeyById() throws Exception {
        UUID tenantA = createOrganization("reader");
        UUID tenantB = createOrganization("owner");
        UUID foreignProject = insertProject(tenantB, "secret-project");

        try (Connection connection = appDataSource.getConnection()) {
            bindTenant(connection, tenantA);
            try (PreparedStatement statement = connection.prepareStatement(
                    "select name from org_projects where id = ?")) {
                statement.setObject(1, foreignProject);
                try (ResultSet rows = statement.executeQuery()) {
                    assertThat(rows.next())
                            .as("a direct lookup by primary key must still be blocked by RLS")
                            .isFalse();
                }
            }
        }
    }

    @Test
    @DisplayName("a tenant cannot insert a row owned by another tenant")
    void cannotInsertIntoAnotherTenant() throws Exception {
        UUID tenantA = createOrganization("writer");
        UUID tenantB = createOrganization("victim");

        try (Connection connection = appDataSource.getConnection()) {
            bindTenant(connection, tenantA);
            assertThatThrownBy(() -> insertProject(connection, tenantB, "smuggled"))
                    .as("WITH CHECK must reject a cross-tenant insert, not silently drop it")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("row-level security");
        }
    }

    @Test
    @DisplayName("a tenant cannot move one of its rows to another tenant")
    void cannotReassignOwnership() throws Exception {
        UUID tenantA = createOrganization("source");
        UUID tenantB = createOrganization("target");
        UUID project = insertProject(tenantA, "movable");

        try (Connection connection = appDataSource.getConnection()) {
            bindTenant(connection, tenantA);
            try (PreparedStatement statement = connection.prepareStatement(
                    "update org_projects set organization_id = ? where id = ?")) {
                statement.setObject(1, tenantB);
                statement.setObject(2, project);
                assertThatThrownBy(statement::executeUpdate)
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("row-level security");
            }
        }
    }

    @Test
    @DisplayName("the application role cannot bypass row level security")
    void appRoleHasNoBypassRls() throws Exception {
        try (Connection connection = migratorDataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "select rolbypassrls from pg_roles where rolname = 'hatis_app'")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getBoolean(1))
                    .as("hatis_app must never hold BYPASSRLS")
                    .isFalse();
        }
    }

    @Test
    @DisplayName("the application role cannot rewrite the audit trail")
    void appRoleCannotRewriteAuditHistory() throws Exception {
        try (Connection connection = migratorDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String table : new String[]{"aud_audit_logs", "wf_history", "dep_deployment_history"}) {
                for (String privilege : new String[]{"UPDATE", "DELETE"}) {
                    try (ResultSet rows = statement.executeQuery(String.format(
                            "select has_table_privilege('hatis_app', '%s', '%s')", table, privilege))) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getBoolean(1))
                                .as("hatis_app must not hold %s on %s", privilege, table)
                                .isFalse();
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("system roles and workflow templates stay visible to every tenant")
    void catalogueRowsRemainVisible() throws Exception {
        UUID tenant = createOrganization("catalogue-consumer");

        try (Connection connection = appDataSource.getConnection()) {
            bindTenant(connection, tenant);
            try (Statement statement = connection.createStatement()) {
                try (ResultSet rows = statement.executeQuery(
                        "select count(*) from auth_roles where system and organization_id is null")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1))
                            .as("the seeded system roles must be readable, or a new tenant has no roles")
                            .isGreaterThan(0);
                }
                try (ResultSet rows = statement.executeQuery(
                        "select count(*) from wf_definitions where organization_id is null")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1))
                            .as("the seeded workflow template must be readable")
                            .isGreaterThan(0);
                }
            }
        }
    }

    @Test
    @DisplayName("a tenant cannot rewrite the shared catalogue")
    void catalogueIsReadOnlyForTenants() throws Exception {
        UUID tenant = createOrganization("catalogue-writer");

        try (Connection connection = appDataSource.getConnection()) {
            bindTenant(connection, tenant);
            try (Statement statement = connection.createStatement()) {
                assertThatThrownBy(() -> statement.execute(
                        "insert into auth_roles (id, organization_id, code, name, system)"
                                + " values (gen_random_uuid(), null, 'SUPERUSER', 'Superuser', true)"))
                        .isInstanceOf(SQLException.class);
            }
        }
    }

    // ------------------------------------------------------------- helpers

    /**
     * Binds a tenant to a connection for the work that follows.
     *
     * <p>{@code set_config(..., true)} is transaction-local, and a JDBC connection
     * defaults to autocommit - where every statement is its own transaction. Without
     * turning autocommit off, the setting is discarded the moment this statement
     * commits, the next statement runs unbound, and every query returns zero rows.
     * That silently makes isolation tests pass for the wrong reason: nothing is
     * visible, so nothing can leak. Verified against PostgreSQL 16 - the same policy
     * returns 0 rows under autocommit and exactly the bound tenant's rows inside a
     * transaction.
     */
    private static void bindTenant(Connection connection, UUID organizationId) throws SQLException {
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement("select set_config(?, ?, true)")) {
            statement.setString(1, "hatis.organization_id");
            statement.setString(2, organizationId.toString());
            statement.execute();
        }
    }

    private int countProjectsAs(UUID organizationId) throws SQLException {
        try (Connection connection = appDataSource.getConnection()) {
            bindTenant(connection, organizationId);
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("select count(*) from org_projects")) {
                assertThat(rows.next()).isTrue();
                return rows.getInt(1);
            }
        }
    }

    /** Created as the migrator, which owns the schema and is not subject to RLS. */
    private UUID createOrganization(String slug) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection connection = migratorDataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "insert into org_organizations (id, organization_id, name, slug, plan_code,"
                             + " isolation_mode, status) values (?, ?, ?, ?, 'starter',"
                             + " 'SHARED_SCHEMA', 'ACTIVE')")) {
            // organization_id equals id: the organization is its own tenant root,
            // which is what the uq_org_organizations_self constraint asserts.
            statement.setObject(1, id);
            statement.setObject(2, id);
            statement.setString(3, slug);
            statement.setString(4, slug);
            statement.executeUpdate();
        }
        return id;
    }

    private UUID insertProject(UUID organizationId, String slug) throws SQLException {
        try (Connection connection = migratorDataSource.getConnection()) {
            return insertProject(connection, organizationId, slug);
        }
    }

    private UUID insertProject(Connection connection, UUID organizationId, String slug) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into org_projects (id, organization_id, name, slug, status)"
                        + " values (?, ?, ?, ?, 'ACTIVE')")) {
            statement.setObject(1, id);
            statement.setObject(2, organizationId);
            statement.setString(3, slug);
            statement.setString(4, slug);
            statement.executeUpdate();
        }
        return id;
    }
}
