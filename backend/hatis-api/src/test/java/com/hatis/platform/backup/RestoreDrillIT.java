package com.hatis.platform.backup;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The restore drill that doc 17 says must exist: a backup that is taken, restored
 * into a fresh instance, and verified by the runbook's own step 3.
 *
 * <p>{@code docs/architecture/17-backup-disaster-recovery.md} is blunt about it —
 * "a restore that is not run does not count as a backup" — and §17.7 step 3 is
 * "not optional": {@code aud_verify_chain} for every tenant, migration version
 * matching the running image, row counts within tolerance. This class executes
 * those steps on every build, against two real PostgreSQL 16 instances:
 *
 * <ol>
 *   <li>the source instance is migrated and seeded, including audit chains;</li>
 *   <li>the backup is taken with the tools the runbook names — {@code pg_dump}
 *       for the database and {@code pg_dumpall --globals-only} for the roles,
 *       because roles are cluster-level and a database-only restore lands in a
 *       cluster where {@code hatis_app} does not exist;</li>
 *   <li>the restore goes into a second, fresh instance;</li>
 *   <li>step 3's verifications run against the restored instance, plus the
 *       security posture the restore must not lose.</li>
 * </ol>
 *
 * <h2>What this drill does not cover</h2>
 *
 * Physical recovery — WAL archiving, base backups, point-in-time recovery to an
 * arbitrary timestamp — needs a running estate and stays on the quarterly
 * schedule in §17.8. This drill covers the logical path and, more importantly,
 * the verification discipline: the checks that must pass before a restored
 * platform accepts traffic are the checks that fail this build.
 */
@Testcontainers
@DisplayName("Restore drill: backup, restore into a fresh instance, verify per §17.7")
class RestoreDrillIT {

    private static final String APP_PASSWORD = "integration-test-only";

    @Container
    static final PostgreSQLContainer<?> SOURCE = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("hatis")
            .withUsername("hatis_migrator")
            .withPassword("migrator");

    @Container
    static final PostgreSQLContainer<?> SCRATCH = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("hatis")
            .withUsername("hatis_migrator")
            .withPassword("migrator");

    private static PGSimpleDataSource sourceData;
    private static PGSimpleDataSource scratchData;

    private static Map<String, Long> sourceCounts;
    private static List<String> sourceScripts;
    private static List<String> sourcePolicies;
    private static final List<UUID> TENANTS = new ArrayList<>();

    @BeforeAll
    static void executeTheRunbook() throws Exception {
        migrateAndSeedSource();
        captureBaseline();
        // §17.7 step 2: restore PostgreSQL into a fresh instance. The roles come
        // from the globals dump first — without them the database restore fails on
        // ownership, which is exactly the failure a database-only backup would
        // hide until the day it is needed.
        String globals = mustRun(SOURCE, "pg_dumpall", "-U", SOURCE.getUsername(), "--globals-only");
        String backup = mustRun(SOURCE, "pg_dump", "-U", SOURCE.getUsername(), "-d", "hatis");
        restoreIntoScratch(globals, backup);
        // The migration creates hatis_app without a password so that an operator
        // supplies one; the drill supplies a throwaway value so the role's
        // refusals can be exercised over a real connection.
        try (Connection connection = scratchData.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("alter role hatis_app login password '" + APP_PASSWORD + "'");
        }
    }

    @Test
    @DisplayName("step 3: row counts match the source in every table")
    void theRestoredRowCountsMatchTheSource() throws Exception {
        try (Connection connection = scratchData.getConnection()) {
            assertThat(counts(connection))
                    .as("every table in the restored instance must hold what the source held")
                    .isEqualTo(sourceCounts);
        }
    }

    @Test
    @DisplayName("step 3: the audit chain verifies for every tenant — and the verification can fail")
    void theAuditChainVerifiesAndTheVerificationCanFail() throws Exception {
        try (Connection connection = scratchData.getConnection()) {
            for (UUID tenant : TENANTS) {
                assertThat(verifyChain(connection, tenant))
                        .as("the restored chain for %s must be intact before the platform accepts traffic", tenant)
                        .containsExactly(true, null);
            }

            // The negative control. A verification that cannot report a break is
            // decoration: break one link as a superuser (the role the platform
            // runs as cannot rewrite history) and the same function must name the
            // sequence where the chain no longer holds.
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "update aud_audit_logs set previous_hash = repeat('0', 64)"
                                + " where organization_id = '" + TENANTS.get(0) + "' and sequence = 2");
            }
            assertThat(verifyChain(connection, TENANTS.get(0)))
                    .as("a rewritten link must be reported, at its sequence")
                    .containsExactly(false, 2L);
        }
    }

    @Test
    @DisplayName("step 3: the migration version matches the running image")
    void theRestoredSchemaVersionMatchesTheTree() throws Exception {
        List<String> tree;
        try (java.util.stream.Stream<Path> files = Files.list(migrationDirectory())) {
            tree = files
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("V"))
                    .sorted()
                    .toList();
        }
        try (Connection connection = scratchData.getConnection()) {
            List<String> restored = scripts(connection);
            assertThat(restored)
                    .as("the restored schema must be exactly the migrations this tree ships")
                    .isEqualTo(tree);
            assertThat(sourceScripts).isEqualTo(tree);
        }
    }

    @Test
    @DisplayName("the restore keeps every row level security policy")
    void theRestoreKeepsRowLevelSecurityPolicies() throws Exception {
        try (Connection connection = scratchData.getConnection()) {
            assertThat(policies(connection))
                    .as("a restore that loses the RLS policies restores a platform that isolates nothing")
                    .isEqualTo(sourcePolicies);
        }
    }

    @Test
    @DisplayName("the restored roles and grants still refuse history rewrites")
    void theRestoredRolesKeepTheSecurityPosture() throws Exception {
        try (Connection connection = scratchData.getConnection();
             Statement statement = connection.createStatement();
             ResultSet roles = statement.executeQuery(
                     "select rolbypassrls from pg_roles where rolname = 'hatis_app'")) {
            assertThat(roles.next()).as("the globals restore must recreate hatis_app").isTrue();
            assertThat(roles.getBoolean(1))
                    .as("hatis_app must not gain BYPASSRLS from the backup")
                    .isFalse();
        }

        PGSimpleDataSource app = dataSource(SCRATCH, "hatis_app", APP_PASSWORD);
        try (Connection connection = app.getConnection();
             Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate("update aud_audit_logs set action = action"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> statement.executeUpdate("delete from aud_audit_logs"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
        }
    }

    // ---------------------------------------------------------------- runbook

    private static void migrateAndSeedSource() throws Exception {
        sourceData = dataSource(SOURCE, SOURCE.getUsername(), SOURCE.getPassword());
        scratchData = dataSource(SCRATCH, SCRATCH.getUsername(), SCRATCH.getPassword());

        var result = Flyway.configure()
                .dataSource(sourceData)
                .locations("filesystem:" + migrationDirectory())
                .cleanDisabled(false)
                .load()
                .migrate();
        if (result.migrationsExecuted == 0) {
            throw new IllegalStateException("Flyway applied no migrations from " + migrationDirectory());
        }

        try (Connection connection = sourceData.getConnection()) {
            TENANTS.add(seedTenant(connection, "drill-tenant-a"));
            TENANTS.add(seedTenant(connection, "drill-tenant-b"));
        }
    }

    private static void captureBaseline() throws Exception {
        try (Connection connection = sourceData.getConnection()) {
            sourceCounts = counts(connection);
            sourceScripts = scripts(connection);
            sourcePolicies = policies(connection);
        }
    }

    private static void restoreIntoScratch(String globals, String backup) throws Exception {
        Path globalsFile = Files.createTempFile("hatis-drill-globals", ".sql");
        Path backupFile = Files.createTempFile("hatis-drill-backup", ".sql");
        Files.writeString(globalsFile, globals, StandardCharsets.UTF_8);
        Files.writeString(backupFile, backup, StandardCharsets.UTF_8);
        SCRATCH.copyFileToContainer(MountableFile.forHostPath(globalsFile), "/tmp/globals.sql");
        SCRATCH.copyFileToContainer(MountableFile.forHostPath(backupFile), "/tmp/backup.sql");

        // Roles first, without ON_ERROR_STOP: the dump recreates every role in the
        // cluster, including the bootstrap superuser that already exists here, and
        // that one "already exists" error is expected. The database restore below
        // stops at the first real error.
        mustRun(SCRATCH, "psql", "-U", SCRATCH.getUsername(), "-d", "postgres", "-f", "/tmp/globals.sql");
        mustRun(SCRATCH, "dropdb", "-U", SCRATCH.getUsername(), "--if-exists", "hatis");
        mustRun(SCRATCH, "createdb", "-U", SCRATCH.getUsername(), "hatis");
        mustRun(SCRATCH, "psql", "-U", SCRATCH.getUsername(), "-d", "hatis",
                "-v", "ON_ERROR_STOP=1", "-f", "/tmp/backup.sql");
    }

    private static String mustRun(PostgreSQLContainer<?> container, String... command) throws Exception {
        ExecResult result = container.execInContainer(command);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("runbook step failed: " + String.join(" ", command)
                    + " (exit " + result.getExitCode() + "): " + result.getStderr());
        }
        return result.getStdout();
    }

    // ------------------------------------------------------------- verification

    private static Map<String, Long> counts(Connection connection) throws SQLException {
        Map<String, Long> result = new TreeMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet tables = statement.executeQuery(
                     "select table_name from information_schema.tables"
                             + " where table_schema = 'public' and table_type = 'BASE TABLE'")) {
            while (tables.next()) {
                String table = tables.getString(1);
                try (ResultSet count = statement.executeQuery("select count(*) from \"" + table + "\"")) {
                    count.next();
                    result.put(table, count.getLong(1));
                }
            }
        }
        return result;
    }

    private static List<String> scripts(Connection connection) throws SQLException {
        List<String> result = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "select script from flyway_schema_history where type = 'SQL' order by installed_rank")) {
            while (rows.next()) {
                result.add(rows.getString(1));
            }
        }
        return result;
    }

    private static List<String> policies(Connection connection) throws SQLException {
        List<String> result = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "select tablename || ':' || policyname from pg_policies"
                             + " where schemaname = 'public' order by 1")) {
            while (rows.next()) {
                result.add(rows.getString(1));
            }
        }
        return result;
    }

    /** One row per call: {@code ok} and {@code broken_at}, as the function reports them. */
    private static List<Object> verifyChain(Connection connection, UUID tenant) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select ok, broken_at from aud_verify_chain(?)")) {
            statement.setObject(1, tenant);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                // An ArrayList because an intact chain reports broken_at as null,
                // and List.of refuses null elements.
                List<Object> report = new ArrayList<>();
                report.add(rows.getBoolean(1));
                report.add(rows.getObject(2));
                return report;
            }
        }
    }

    // ------------------------------------------------------------------- seeds

    private static UUID seedTenant(Connection connection, String slug) throws SQLException {
        UUID organization = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into org_organizations (id, organization_id, name, slug, plan_code,"
                        + " isolation_mode, status) values (?, ?, ?, ?, 'starter',"
                        + " 'SHARED_SCHEMA', 'ACTIVE')")) {
            // organization_id equals id: the organization is its own tenant root,
            // which is what the uq_org_organizations_self constraint asserts.
            statement.setObject(1, organization);
            statement.setObject(2, organization);
            statement.setString(3, slug);
            statement.setString(4, slug);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into org_projects (id, organization_id, name, slug, status)"
                        + " values (?, ?, ?, ?, 'ACTIVE')")) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, organization);
            statement.setString(3, slug + "-project");
            statement.setString(4, slug + "-project");
            statement.executeUpdate();
        }
        seedAuditChain(connection, organization, slug);
        return organization;
    }

    /**
     * Three linked audit rows. The linkage is the production rule — each row's
     * previous_hash is the prior row's record_hash, and the first is 64 zeros —
     * which is what {@code aud_verify_chain} walks. The record hashes are
     * computed over drill content rather than the canonical record AuditService
     * builds; the restore drill exercises the chain's survival across backup and
     * restore, not the hashing scheme.
     */
    private static void seedAuditChain(Connection connection, UUID organization, String slug)
            throws SQLException {
        String previous = "0".repeat(64);
        for (int sequence = 1; sequence <= 3; sequence++) {
            String recordHash = sha256(previous + "|" + organization + "|" + sequence);
            try (PreparedStatement statement = connection.prepareStatement(
                    "insert into aud_audit_logs"
                            + " (id, organization_id, actor_type, action, result,"
                            + " previous_hash, record_hash, sequence, occurred_at)"
                            + " values (?, ?, 'SYSTEM', ?, 'SUCCESS', ?, ?, ?, now())")) {
                statement.setObject(1, UUID.randomUUID());
                statement.setObject(2, organization);
                statement.setString(3, slug + ".action." + sequence);
                statement.setString(4, previous);
                statement.setString(5, recordHash);
                statement.setLong(6, sequence);
                statement.executeUpdate();
            }
            previous = recordHash;
        }
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    // ----------------------------------------------------------------- helpers

    /**
     * Where the migration scripts are read from. The same two candidates as the
     * other integration tests — the module directory and the repository root —
     * because the relative path depends on the working directory given to the
     * runner. Unlike those tests there is no classpath fallback: the drill
     * compares the applied migrations against the files in the tree, and a
     * classpath location cannot be listed.
     */
    private static Path migrationDirectory() {
        for (String candidate : new String[]{
                "src/main/resources/db/migration",
                "backend/hatis-api/src/main/resources/db/migration"}) {
            Path path = Path.of(candidate);
            if (Files.isDirectory(path)) {
                return path.toAbsolutePath();
            }
        }
        throw new IllegalStateException("no migration directory found from " + Path.of("").toAbsolutePath());
    }

    private static PGSimpleDataSource dataSource(PostgreSQLContainer<?> container, String user, String password) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(container.getJdbcUrl());
        source.setUser(user);
        source.setPassword(password);
        return source;
    }
}
