package com.hatis.platform.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The datasource configuration that actually ships, exercised against a real PostgreSQL.
 *
 * <h2>The gap this closes</h2>
 *
 * pgjdbc sends a Java {@code String} as {@code character varying} unless
 * {@code stringtype=unspecified} is set, and PostgreSQL will not assign
 * {@code character varying} to {@code jsonb}. Six entities map a {@code String} onto a
 * {@code jsonb} column, so without that property no outbox entry, audit record, content
 * version, content type, inbound integration or idempotency response can be written at all.
 *
 * <p>{@code OutboxEntryPersistenceIT} proves the Hibernate half: with the property reaching
 * the driver, a mapped entity round-trips. But it reaches the driver there through
 * {@code hibernate.connection.stringtype}, on a bootstrap that builds its own connections.
 * Production does not work that way. {@code application.yml} sets the property under
 * {@code spring.datasource.hikari.data-source-properties}, and Hikari applies it to the
 * pool. Those are different mechanisms, and nothing had shown that the shipped one works —
 * so the fix could have been correct in a test and absent in production.
 *
 * <p>This reads the property back out of {@code application.yml} itself rather than
 * restating it, which is what stops the two from drifting. Delete the line from the YAML
 * and {@link #theShippedConfigurationSetsTheDriverProperty} fails, rather than the suite
 * quietly continuing to test a configuration nothing uses.
 *
 * <h2>Why the role is asserted</h2>
 *
 * Testcontainers' default user is a superuser, and a superuser bypasses row level security
 * regardless of {@code FORCE}. A test that silently ran as one would prove nothing about
 * what the application can do, which is why {@link #thePoolConnectsAsTheApplicationRole}
 * exists and why every connection here is opened as {@code hatis_app}.
 */
@Testcontainers
@DisplayName("The shipped datasource configuration, against PostgreSQL")
class JsonbDataSourceConfigurationIT {

    private static final String APP_PASSWORD = "integration-test-only";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("hatis")
            .withUsername("hatis_migrator")
            .withPassword("migrator");

    @BeforeAll
    static void migrate() throws Exception {
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
    }

    @Test
    @DisplayName("application.yml sets the driver property the jsonb columns depend on")
    void theShippedConfigurationSetsTheDriverProperty() {
        probeConfigurationVisibility();

        Map<String, Object> properties = shippedDataSourceProperties();

        assertThat(properties)
                .as("read out of application.yml rather than restated here, so the test "
                        + "cannot pass against a configuration the application does not use")
                .containsEntry("stringtype", "unspecified");
    }

    @Test
    @DisplayName("a jsonb write succeeds through a pool built from the shipped configuration")
    void aJsonbWriteThroughTheConfiguredPoolSucceeds() throws Exception {
        UUID organizationId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        try (HikariDataSource pool = poolWith(shippedDataSourceProperties());
             Connection connection = pool.getConnection()) {
            insertOutboxRow(connection, organizationId, eventId);

            // Read before committing. The tenant binding insertOutboxRow sets is
            // transaction-local, and row level security applies to reads too, so the same
            // query after commit returns nothing - the row is there, the binding is not.
            // Verified against this schema: visible in the transaction, invisible to the
            // same role once it commits, still present to a role outside row level
            // security. Asserting after the commit would have read as a failed write what
            // was actually a successful one.
            assertThat(jsonbTypeOf(connection, eventId))
                    .as("the same setString call Hibernate makes, through the pool the "
                            + "application actually builds")
                    .isEqualTo("object");

            connection.commit();
        }

        // Committed, not merely accepted: read back through the migrator, which is outside
        // row level security, so this is a statement about durability and not about policy.
        assertThat(jsonbTypeOfAsMigrator(eventId))
                .as("the row survived the commit, stored as a jsonb object")
                .isEqualTo("object");
    }

    @Test
    @DisplayName("the identical write fails through a pool without those properties")
    void theSameWriteFailsThroughAPoolWithoutThoseProperties() {
        UUID organizationId = UUID.randomUUID();

        // Same URL, same role, same statement - only the shipped properties are missing,
        // which is the shape of a deployment that lost them.
        try (HikariDataSource pool = poolWith(Map.of());
             Connection connection = pool.getConnection()) {
            assertThatThrownBy(() -> insertOutboxRow(connection, organizationId, UUID.randomUUID()))
                    .as("without stringtype the parameter is character varying, and "
                            + "PostgreSQL refuses to assign that to jsonb")
                    .hasMessageContaining("column \"payload\" is of type jsonb"
                            + " but expression is of type character varying");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("the pool connects as the application role, not as a superuser")
    void thePoolConnectsAsTheApplicationRole() throws Exception {
        try (HikariDataSource pool = poolWith(shippedDataSourceProperties());
             Connection connection = pool.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "select current_user, usesuper from pg_user where usename = current_user")) {

            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1))
                    .as("a superuser bypasses row level security whatever the policies say, "
                            + "so a test running as one would prove nothing")
                    .isEqualTo("hatis_app");
            assertThat(rows.getBoolean(2)).isFalse();
        }
    }

    /**
     * {@code spring.datasource.hikari.data-source-properties} from the application's own
     * configuration file.
     *
     * <p>Read from the module source tree rather than from the classpath, for the same
     * reason {@link #migrationLocation} does it. The first version of this test used
     * {@code getResourceAsStream("/application.yml")} and it returned {@code null} under
     * failsafe, even though the file is a tracked main resource and the compiled classes
     * beside it load fine; {@link #probeConfigurationVisibility} records what that
     * classloader actually resolves, because whether the packaged application can see its
     * own configuration is not something to guess at.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> shippedDataSourceProperties() {
        try (InputStream configuration = Files.newInputStream(shippedConfigurationFile())) {
            Map<String, Object> root = new Yaml().load(configuration);
            Map<String, Object> spring = (Map<String, Object>) root.get("spring");
            Map<String, Object> datasource = (Map<String, Object>) spring.get("datasource");
            Map<String, Object> hikari = (Map<String, Object>) datasource.get("hikari");
            Map<String, Object> properties =
                    (Map<String, Object>) hikari.get("data-source-properties");
            assertThat(properties)
                    .as("application.yml has no spring.datasource.hikari.data-source-properties")
                    .isNotNull();
            return properties;
        } catch (IOException e) {
            throw new IllegalStateException("could not read application.yml", e);
        }
    }

    private static java.nio.file.Path shippedConfigurationFile() {
        for (String candidate : new String[]{
                "src/main/resources/application.yml",
                "backend/hatis-api/src/main/resources/application.yml"}) {
            java.nio.file.Path path = java.nio.file.Path.of(candidate);
            if (Files.isRegularFile(path)) {
                return path.toAbsolutePath();
            }
        }
        throw new IllegalStateException("application.yml not found; working directory is "
                + java.nio.file.Path.of(".").toAbsolutePath());
    }

    /**
     * Prints, under a marker the build report collects, what the test classloader resolves
     * for {@code application.yml} and where the class itself was loaded from. A main
     * resource that is invisible to the classloader next to its own compiled classes is
     * worth knowing about, and this is the cheapest way to find out which it is.
     */
    private static void probeConfigurationVisibility() {
        ClassLoader loader = JsonbDataSourceConfigurationIT.class.getClassLoader();
        System.out.println("CLASSPATH-PROBE workingDirectory="
                + java.nio.file.Path.of(".").toAbsolutePath());
        System.out.println("CLASSPATH-PROBE classLoader=" + loader);
        System.out.println("CLASSPATH-PROBE classLoadedFrom="
                + JsonbDataSourceConfigurationIT.class
                        .getResource("JsonbDataSourceConfigurationIT.class"));
        System.out.println("CLASSPATH-PROBE applicationYmlViaClass="
                + JsonbDataSourceConfigurationIT.class.getResource("/application.yml"));
        System.out.println("CLASSPATH-PROBE applicationYmlViaClassLoader="
                + loader.getResource("application.yml"));
        System.out.println("CLASSPATH-PROBE migrationDirViaClassLoader="
                + loader.getResource("db/migration"));
    }

    /**
     * A pool configured the way {@code application.yml} configures the production one:
     * JDBC URL plus the data-source properties, which Hikari hands to the driver.
     */
    private static HikariDataSource poolWith(Map<String, Object> dataSourceProperties) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername("hatis_app");
        config.setPassword(APP_PASSWORD);
        config.setMaximumPoolSize(2);
        config.setPoolName("jsonb-config-test");
        dataSourceProperties.forEach((name, value) ->
                config.addDataSourceProperty(name, String.valueOf(value)));
        return new HikariDataSource(config);
    }

    /**
     * Writes an outbox row the way Hibernate does: a plain {@code setString} into the
     * {@code jsonb} column, with no cast in the SQL to hide the parameter's type.
     */
    private static void insertOutboxRow(Connection connection, UUID organizationId, UUID eventId)
            throws SQLException {
        connection.setAutoCommit(false);
        try (PreparedStatement setting = connection.prepareStatement(
                "select set_config('hatis.organization_id', ?, true)")) {
            setting.setString(1, organizationId.toString());
            setting.execute();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into plat_outbox (id, aggregate_type, event_type, organization_id,"
                        + " occurred_at, payload) values (?, 'content_item', ?, ?, now(), ?)")) {
            statement.setObject(1, eventId);
            statement.setString(2, "cms.content.published");
            statement.setObject(3, organizationId);
            statement.setString(4, "{\"eventType\":\"cms.content.published\","
                    + "\"eventVersion\":1,\"data\":{\"itemId\":\"abc\"}}");
            statement.execute();
        }
    }

    private static String jsonbTypeOf(Connection connection, UUID eventId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select jsonb_typeof(payload) from plat_outbox where id = ?")) {
            statement.setObject(1, eventId);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
    }

    /**
     * The stored type read through the migrator, which is the container's superuser and so
     * sits outside row level security. Used only to confirm the row committed; who may read
     * it is {@code TenantIsolationIT}'s subject, not this test's.
     */
    private static String jsonbTypeOfAsMigrator(UUID eventId) throws SQLException {
        try (Connection connection =
                     dataSource(POSTGRES.getUsername(), POSTGRES.getPassword()).getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select jsonb_typeof(payload) from plat_outbox where id = ?")) {
            statement.setObject(1, eventId);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
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
