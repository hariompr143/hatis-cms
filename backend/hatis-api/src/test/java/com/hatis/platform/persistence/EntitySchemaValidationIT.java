package com.hatis.platform.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.tool.schema.spi.SchemaManagementException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every entity mapping in the platform, checked against the schema the migrations
 * actually produce.
 *
 * <h2>What had never happened</h2>
 *
 * The sources declare 28 {@code @Entity} classes. Before this test, exactly three had ever
 * been registered with a Hibernate session — {@code OutboxEntry}, {@code WebhookEndpoint}
 * and {@code WebhookDelivery} — because those are the only three an integration test
 * happened to touch. The other 25 had never met a database in any form.
 *
 * <p>That is not a theoretical gap. {@code application.yml} sets
 * {@code spring.jpa.hibernate.ddl-auto: validate}, so the first time this application is
 * deployed Hibernate compares all 28 mappings against the migrated schema and refuses to
 * start if any of them disagrees. Nothing in the repository had ever run that comparison,
 * so a mapping that drifted from its table would have been found by a customer's deploy
 * rather than by the build.
 *
 * <p>The first run of this test found a real disagreement rather than passing, which is
 * the result that justifies it: {@code aud_audit_logs.previous_hash} is {@code char(64)}
 * and the mapping declared only a length, so Hibernate inferred {@code varchar}. Setting
 * {@code columnDefinition = "char(64")} did <em>not</em> fix it, and the reason is worth
 * keeping: Hibernate takes {@code columnDefinition} as the expected type <em>name</em> but
 * still derives the JDBC type code from the Java field type, so a {@code String} keeps
 * expecting {@code Types.VARCHAR} whatever the annotation says. The mismatch is between
 * {@code Types#CHAR} and {@code Types#VARCHAR}, and no amount of annotation text closes it.
 *
 * <p>{@code tools/check_entity_schema.py} narrows the gap but cannot close it: it parses
 * annotations and the migration SQL textually, so it cannot see a column added by a later
 * {@code ALTER}, cannot apply Hibernate's naming strategy, and cannot know what Hibernate
 * infers when an annotation is left off. Only Hibernate can answer the question, which is
 * why the question is put to Hibernate.
 *
 * <h2>Why the discovery is counted</h2>
 *
 * A validation test that silently examines fewer classes than exist is worse than no test,
 * because it reports green. {@link #theDiscoveryFindsEveryEntityTheSourcesDeclare} compares
 * the classes this test loads against the number of {@code @Entity} annotations in the
 * sources, so an entity added later is validated automatically and one this test fails to
 * see is reported rather than skipped.
 *
 * <h2>Why {@code validate} and not {@code none}</h2>
 *
 * {@code OutboxEntryPersistenceIT} deliberately sets no {@code hbm2ddl.auto}, and says that
 * letting Hibernate validate the schema "would test Hibernate's idea of the schema rather
 * than the one the migrations actually produce". That is right for the generative modes —
 * {@code create} and {@code update} would let Hibernate change the schema under test — but
 * it is backwards for {@code validate}, which writes nothing. {@code validate} is precisely
 * the comparison between Hibernate's idea and the migrations' output, and it is the one the
 * application performs on every start.
 */
@Testcontainers
@DisplayName("Every entity mapping against the migrated schema")
class EntitySchemaValidationIT {

    private static final String APP_PASSWORD = "integration-test-only";

    /**
     * An annotation, not a prefix: {@code \b} stops this matching {@code @EntityGraph}.
     */
    private static final Pattern ENTITY_ANNOTATION = Pattern.compile("@Entity\\b");

    private static final Pattern ENTITY_CLASS = Pattern.compile(
            "@Entity\\b.*?public\\s+(?:static\\s+)?class\\s+(\\w+)", Pattern.DOTALL);

    private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE);

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
    @DisplayName("the discovery finds every entity the sources declare")
    void theDiscoveryFindsEveryEntityTheSourcesDeclare() {
        List<Class<?>> discovered = allEntityClasses();
        int declared = entityAnnotationsInSources();

        assertThat(discovered)
                .as("an empty list here would make the validation test below pass without "
                        + "examining anything")
                .isNotEmpty();
        assertThat(discovered)
                .as("every @Entity in the sources must be validated; a class this test "
                        + "cannot see is a mapping nobody has checked")
                .hasSize(declared);
        assertThat(discovered)
                .as("the same entity must not be registered twice, which would hide a "
                        + "discovery bug behind a correct-looking count")
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("every mapping agrees with the schema the migrations produce")
    void everyEntityMappingAgreesWithTheMigratedSchema() {
        List<Class<?>> entities = allEntityClasses();
        List<String> disagreements = new ArrayList<>();

        // One session factory per entity rather than one for all of them. Hibernate
        // reports the first disagreement it reaches and stops, so a single build surfaces
        // one problem per run and costs a build cycle for each. Validating them separately
        // lists every disagreement at once, which is the difference between finding this
        // out in one run and finding it out over a week.
        for (Class<?> entity : entities) {
            try (SessionFactory sessionFactory = sessionFactoryWith(List.of(entity), true)) {
                assertThat(sessionFactory.getMetamodel().getEntities())
                        .as("%s must be the only entity registered, or a pass says nothing "
                                + "about it", entity.getSimpleName())
                        .hasSize(1);
            } catch (RuntimeException e) {
                disagreements.add(entity.getSimpleName() + " -> " + firstLineOf(e));
            }
        }

        assertThat(disagreements)
                .as("each of the %d mappings was validated against the migrated schema on "
                        + "its own, so this is the complete list of disagreements rather "
                        + "than only the first", entities.size())
                .isEmpty();
    }

    private static String firstLineOf(RuntimeException e) {
        String message = String.valueOf(e.getMessage());
        int end = message.indexOf('\n');
        return e.getClass().getSimpleName() + ": "
                + (end < 0 ? message : message.substring(0, end)).trim();
    }

    @Test
    @DisplayName("validation catches a mapping that disagrees with the schema")
    void validationCatchesAMappingThatDisagreesWithTheSchema() {
        assertThatThrownBy(() -> sessionFactoryWith(List.of(DeliberatelyWrongMapping.class), true))
                .as("without this, a validate that silently checked nothing would look "
                        + "exactly like one that checked everything and found no problem")
                .isInstanceOf(SchemaManagementException.class)
                .hasMessageContaining("column_that_does_not_exist");
    }

    /**
     * Mapped onto a real table, with one column that is not in it. Exists only so the test
     * above can show the validation is capable of failing.
     */
    @Entity
    @Table(name = "plat_outbox")
    static class DeliberatelyWrongMapping {

        @Id
        @Column(name = "id")
        UUID id;

        @Column(name = "column_that_does_not_exist")
        String notAColumn;
    }

    private static SessionFactory sessionFactoryWith(
            List<Class<?>> entities, boolean validate) {
        Configuration configuration = new Configuration()
                .setProperty("jakarta.persistence.jdbc.url", POSTGRES.getJdbcUrl())
                .setProperty("jakarta.persistence.jdbc.user", "hatis_app")
                .setProperty("jakarta.persistence.jdbc.password", APP_PASSWORD)
                .setProperty("jakarta.persistence.jdbc.driver", "org.postgresql.Driver");
        if (validate) {
            configuration.setProperty("hibernate.hbm2ddl.auto", "validate");
        }
        entities.forEach(configuration::addAnnotatedClass);
        return configuration.buildSessionFactory();
    }

    /**
     * Every {@code @Entity} class declared under {@code backend}, found by reading the
     * sources.
     *
     * <p>Read from the source tree rather than by scanning the classpath, for the reason
     * {@code JsonbDataSourceConfigurationIT} records: the failsafe classloader in this
     * build does not resolve the module's own {@code target/classes}, so a classpath scan
     * finds nothing and reports it as an empty result.
     */
    private static List<Class<?>> allEntityClasses() {
        Path backend = backendRoot();
        List<Class<?>> entities = new ArrayList<>();
        for (Path source : mainSources(backend)) {
            try {
                String text = Files.readString(source);
                if (!text.contains("@Entity")) {
                    continue;
                }
                Matcher packageName = PACKAGE.matcher(text);
                if (!packageName.find()) {
                    continue;
                }
                String prefix = packageName.group(1) + ".";
                String outer = source.getFileName().toString().replace(".java", "");
                Matcher declared = ENTITY_CLASS.matcher(text);
                while (declared.find()) {
                    String simple = declared.group(1);
                    // A class whose name is not the file's name is a nested one, so its
                    // binary name is Outer$Nested rather than a sibling in the package.
                    String binaryName = prefix
                            + (simple.equals(outer) ? simple : outer + "$" + simple);
                    entities.add(load(binaryName, source));
                }
            } catch (IOException e) {
                throw new IllegalStateException("could not read " + source, e);
            }
        }
        return entities;
    }

    private static Class<?> load(String binaryName, Path source) {
        try {
            return Class.forName(binaryName);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "declared in " + source + " but not on the classpath: " + binaryName, e);
        }
    }

    private static int entityAnnotationsInSources() {
        int count = 0;
        for (Path source : mainSources(backendRoot())) {
            try {
                Matcher annotations = ENTITY_ANNOTATION.matcher(Files.readString(source));
                while (annotations.find()) {
                    count++;
                }
            } catch (IOException e) {
                throw new IllegalStateException("could not read " + source, e);
            }
        }
        return count;
    }

    private static List<Path> mainSources(Path backend) {
        try (Stream<Path> walk = Files.walk(backend)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.toString().contains("/src/main/java/"))
                    // Generated and copied sources under target/ would be discovered a
                    // second time, and a duplicate would fail for the wrong reason.
                    .filter(path -> !path.toString().contains("/target/"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("could not walk " + backend, e);
        }
    }

    private static Path backendRoot() {
        for (String candidate : new String[]{"..", "backend", "../backend"}) {
            Path path = Path.of(candidate);
            if (Files.isDirectory(path.resolve("hatis-shared/src/main/java"))) {
                return path.toAbsolutePath().normalize();
            }
        }
        throw new IllegalStateException("could not locate the backend module tree from "
                + Path.of(".").toAbsolutePath());
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
            Path path = Path.of(candidate);
            if (Files.isDirectory(path)) {
                return "filesystem:" + path.toAbsolutePath();
            }
        }
        return "classpath:db/migration";
    }
}
