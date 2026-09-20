package com.hatis.platform.wiring;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application's own configuration, loaded by Spring Boot, against a real PostgreSQL.
 *
 * <h2>What had never happened</h2>
 *
 * Until this test no Spring context had ever been started anywhere in the repository. Every
 * other test constructs its subject directly, which is the right choice for a unit test and
 * the wrong one for the question "does this application boot". The consequence was that
 * {@code application.yml} was unexercised: nothing had ever bound
 * {@code spring.datasource.hikari} onto a real {@code HikariConfig} through Spring, and
 * nothing had ever run the {@code spring.jpa.hibernate.ddl-auto: validate} that the
 * application performs on every start.
 *
 * <p>Two other tests get close and neither is this. {@code JsonbDataSourceConfigurationIT}
 * runs Boot's {@code Binder} over the file by hand, which proves the binding mechanism but
 * not that the application invokes it. {@code EntitySchemaValidationIT} builds a
 * {@code SessionFactory} with {@code validate} itself, which proves the mappings agree with
 * the schema but not that the application asks. This test asks the application.
 *
 * <h2>Scope, stated plainly</h2>
 *
 * The context imports only the datasource and JPA autoconfiguration. The full application
 * would also want Redis, an object storage provider, a secret store and a token issuer, and
 * a test that failed because Redis was absent would say nothing about the wiring. What is
 * asserted here is the part that had never been touched at all, and it is asserted against
 * the shipped {@code application.yml} rather than a copy of it.
 *
 * <p>{@code ddl-auto: validate} runs while the {@code EntityManagerFactory} is created, so a
 * mapping that disagreed with the migrated schema would stop the context from starting and
 * fail every test in this class. {@link #hibernateValidatedTheMappingsAtBoot} pins that the
 * setting is actually on, because a configuration that silently dropped it would let the
 * other assertions pass for the wrong reason.
 */
@SpringBootTest(
        classes = ApplicationWiringIT.Wiring.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.config.location=" + ApplicationWiringIT.CONFIG_LOCATIONS)
@DisplayName("The shipped application.yml, loaded by Spring Boot")
class ApplicationWiringIT {

    private static final String APP_PASSWORD = "integration-test-only";

    /**
     * Read from the module source tree rather than the classpath: the failsafe classloader
     * in this build does not resolve the module's own {@code target/classes}, which
     * {@code JsonbDataSourceConfigurationIT} records. Both candidate working directories
     * are offered, and {@code optional:} means a missing file leaves the context without
     * configuration rather than failing to start for an unrelated reason — the assertions
     * below then fail loudly, which is the outcome wanted.
     */
    static final String CONFIG_LOCATIONS =
            "optional:file:src/main/resources/application.yml,"
                    + "optional:file:backend/hatis-api/src/main/resources/application.yml";

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("hatis")
            .withUsername("hatis_migrator")
            .withPassword("migrator");

    /**
     * Started and migrated in a static initialiser rather than with {@code @Container} and
     * {@code @BeforeAll}, because Spring's extension creates the application context before
     * {@code @BeforeAll} runs. Hibernate would otherwise validate an empty database.
     */
    static {
        POSTGRES.start();
        migrate();
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @AfterAll
    static void stopContainer() {
        POSTGRES.stop();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        // Only the coordinates come from the container. Everything else - the pool name,
        // the pool size, the driver properties - has to arrive from application.yml, or
        // the test asserts nothing about the shipped configuration.
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "hatis_app");
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Test
    @DisplayName("the shipped configuration produces the production connection pool")
    void theShippedConfigurationProducesTheProductionPool() {
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        HikariDataSource pool = (HikariDataSource) dataSource;

        assertThat(pool.getDataSourceProperties())
                .as("bound by Spring from application.yml, not copied here: this is the "
                        + "property every String-to-jsonb write depends on")
                .containsEntry("stringtype", "unspecified");
        assertThat(pool.getPoolName()).isEqualTo("hatis-pool");
        assertThat(pool.getMaximumPoolSize())
                .as("20 only if the HATIS_DB_POOL_MAX placeholder resolved")
                .isEqualTo(20);
    }

    @Test
    @DisplayName("Hibernate validated the mappings against the migrated schema at boot")
    void hibernateValidatedTheMappingsAtBoot() {
        assertThat(entityManagerFactory.getProperties())
                .as("if validate is not actually on, the context starting proves nothing")
                .containsEntry("hibernate.hbm2ddl.auto", "validate");

        // The scan reaching every module is what makes the validation above meaningful: an
        // EntityManagerFactory that knew about three entities would validate three and
        // report success.
        assertThat(entityManagerFactory.getMetamodel().getEntities())
                .as("every @Entity in the platform must be in the metamodel, or validate "
                        + "silently covered less than the schema")
                .hasSizeGreaterThanOrEqualTo(28)
                .extracting(entity -> entity.getJavaType().getSimpleName())
                .contains("OutboxEntry", "AuditLog", "ContentType", "ContentVersion",
                        "InboundIntegration", "IdempotencyRecord", "User", "Organization",
                        "Domain", "Asset", "Deployment");
    }

    @SpringBootConfiguration
    @ImportAutoConfiguration({
            DataSourceAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class
    })
    @EntityScan(basePackages = "com.hatis.platform")
    static class Wiring {
    }

    private static void migrate() {
        PGSimpleDataSource migrator = new PGSimpleDataSource();
        migrator.setUrl(POSTGRES.getJdbcUrl());
        migrator.setUser(POSTGRES.getUsername());
        migrator.setPassword(POSTGRES.getPassword());

        MigrateResult result = Flyway.configure()
                .dataSource(migrator)
                .locations(migrationLocation())
                .cleanDisabled(false)
                .load()
                .migrate();
        if (result.migrationsExecuted == 0) {
            throw new IllegalStateException(
                    "Flyway applied no migrations from " + migrationLocation());
        }

        try (Connection connection = migrator.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("alter role hatis_app login password '" + APP_PASSWORD + "'");
        } catch (Exception e) {
            throw new IllegalStateException("could not make hatis_app usable", e);
        }
    }

    private static String migrationLocation() {
        for (String candidate : new String[]{
                "src/main/resources/db/migration",
                "backend/hatis-api/src/main/resources/db/migration"}) {
            Path path = Path.of(candidate);
            if (Files.isDirectory(path)) {
                return "filesystem:" + path.toAbsolutePath();
            }
        }
        return "classpath:db/migration";
    }
}
