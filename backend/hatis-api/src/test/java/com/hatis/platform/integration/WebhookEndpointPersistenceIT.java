package com.hatis.platform.integration;

import com.hatis.platform.integration.adapter.persistence.IntegrationRepositories;
import com.hatis.platform.integration.adapter.persistence.WebhookDelivery;
import com.hatis.platform.integration.adapter.persistence.WebhookEndpoint;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.hibernate.cfg.Configuration;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The outbound webhook mappings, tested against a real PostgreSQL under forced row level
 * security.
 *
 * <h2>Why this test exists</h2>
 *
 * {@link WebhookEndpoint#getEvents()} is the first PostgreSQL {@code text[]} column the
 * platform maps through Hibernate, and an array mapping is exactly the kind of thing that
 * compiles, passes every unit test, and then fails at the first flush in production. Two
 * specific traps motivated this:
 *
 * <ul>
 *   <li>Hibernate sends a Java {@code String[]} as a {@code varchar[]} parameter, while
 *       the column is {@code text[]}. Those are distinct types. PostgreSQL does
 *       assignment-cast between them here, but that is a fact about PostgreSQL that
 *       should be verified against a running server rather than assumed from the shape of
 *       the annotations.</li>
 *   <li>{@link com.hatis.platform.tenant.TenantIsolationIT} drives the same schema over
 *       raw JDBC, so it would have passed with a completely broken Hibernate mapping. A
 *       green isolation suite was not evidence about this class at all.</li>
 * </ul>
 *
 * <p>Everything runs as {@code hatis_app}, the role the application uses, which has no
 * {@code BYPASSRLS}. Both {@code int_webhook_endpoints} and {@code int_webhook_deliveries}
 * are in the tenant table list, so the last two tests assert the isolation actually
 * reaches the new tables rather than trusting that the migration remembered them.
 *
 * <p>A plain Hibernate {@code SessionFactory} is built directly instead of using
 * {@code @DataJpaTest}: the subject under test is the mapping, and reaching it through
 * Spring's test slicing would make a mapping failure indistinguishable from a wiring
 * failure.
 */
@Testcontainers
@DisplayName("Outbound webhook persistence, against PostgreSQL with row level security")
class WebhookEndpointPersistenceIT {

    private static final String APP_PASSWORD = "integration-test-only";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("hatis")
            .withUsername("hatis_migrator")
            .withPassword("migrator");

    private static EntityManagerFactoryHolder emf;

    /** Keeps the checked Hibernate bootstrap in one place. */
    private record EntityManagerFactoryHolder(org.hibernate.SessionFactory sessionFactory) {
        EntityManager createEntityManager() {
            return sessionFactory.createEntityManager();
        }

        void close() {
            sessionFactory.close();
        }
    }

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

        Configuration configuration = new Configuration()
                .addAnnotatedClass(WebhookEndpoint.class)
                .addAnnotatedClass(WebhookDelivery.class)
                .setProperty("jakarta.persistence.jdbc.url", POSTGRES.getJdbcUrl())
                .setProperty("jakarta.persistence.jdbc.user", "hatis_app")
                .setProperty("jakarta.persistence.jdbc.password", APP_PASSWORD)
                .setProperty("jakarta.persistence.jdbc.driver", "org.postgresql.Driver");
        // No hbm2ddl.auto on purpose: the schema belongs to Flyway, and letting Hibernate
        // generate or validate it here would test Hibernate's idea of the schema rather
        // than the one the migrations actually produce.

        emf = new EntityManagerFactoryHolder(configuration.buildSessionFactory());
    }

    @AfterAll
    static void shutdown() {
        if (emf != null) {
            emf.close();
        }
    }

    @Test
    @DisplayName("the text[] event subscription survives a write and a read")
    void theEventArrayRoundTrips() {
        UUID org = UUID.randomUUID();
        UUID id = asTenant(org, em -> {
            WebhookEndpoint endpoint = WebhookEndpoint.register(org,
                    "https://hooks.acme.example/hatis", "Production releases",
                    List.of("content.published", "asset.updated"),
                    "ciphertext-value", "dek-1");
            em.persist(endpoint);
            return endpoint.getId();
        });

        WebhookEndpoint reloaded = asTenant(org, em -> em.find(WebhookEndpoint.class, id));

        assertThat(reloaded).isNotNull();
        // Sorted by the entity on the way in, which is what makes two identical
        // subscriptions store identically.
        assertThat(reloaded.getEvents())
                .containsExactly("asset.updated", "content.published");
        assertThat(reloaded.getUrl()).isEqualTo("https://hooks.acme.example/hatis");
        assertThat(reloaded.getDescription()).isEqualTo("Production releases");
        assertThat(reloaded.getSecretCiphertext()).isEqualTo("ciphertext-value");
        assertThat(reloaded.getDekId()).isEqualTo("dek-1");
        assertThat(reloaded.isActive()).isTrue();
    }

    @Test
    @DisplayName("an empty subscription round trips as an empty array, not as null")
    void anEmptySubscriptionRoundTrips() {
        UUID org = UUID.randomUUID();
        UUID id = asTenant(org, em -> {
            WebhookEndpoint endpoint = WebhookEndpoint.register(org,
                    "https://hooks.acme.example/all", null, List.of(), "ct", "dek-2");
            em.persist(endpoint);
            return endpoint.getId();
        });

        WebhookEndpoint reloaded = asTenant(org, em -> em.find(WebhookEndpoint.class, id));

        // A null here would reach the dispatcher as "subscribes to nothing" or as an NPE
        // depending on the caller, from a row that means "subscribes to everything".
        assertThat(reloaded.getEvents()).isEmpty();
    }

    @Test
    @DisplayName("a duplicate subscription is stored once")
    void duplicateEventsCollapse() {
        UUID org = UUID.randomUUID();
        UUID id = asTenant(org, em -> {
            WebhookEndpoint endpoint = WebhookEndpoint.register(org,
                    "https://hooks.acme.example/dup", null,
                    List.of("content.published", "content.published", "asset.updated"),
                    "ct", "dek-3");
            em.persist(endpoint);
            return endpoint.getId();
        });

        assertThat(asTenant(org, em -> em.find(WebhookEndpoint.class, id)).getEvents())
                .containsExactly("asset.updated", "content.published");
    }

    @Test
    @DisplayName("delivery bookkeeping survives a write and a read")
    void deliveryBookkeepingRoundTrips() {
        UUID org = UUID.randomUUID();
        UUID endpointId = asTenant(org, em -> {
            WebhookEndpoint endpoint = WebhookEndpoint.register(org,
                    "https://hooks.acme.example/del", null, List.of("content.published"),
                    "ct", "dek-4");
            em.persist(endpoint);
            return endpoint.getId();
        });
        UUID eventId = UUID.randomUUID();
        Instant nextAttempt = Instant.now().plus(60, ChronoUnit.SECONDS).truncatedTo(ChronoUnit.MICROS);

        UUID deliveryId = asTenant(org, em -> {
            WebhookDelivery delivery = WebhookDelivery.pending(org, endpointId, eventId,
                    "content.published");
            delivery.recordFailure(503, "connection refused", nextAttempt, false);
            em.persist(delivery);
            return delivery.getId();
        });

        WebhookDelivery reloaded = asTenant(org, em -> em.find(WebhookDelivery.class, deliveryId));

        assertThat(reloaded.getStatus()).isEqualTo(WebhookDelivery.Status.PENDING);
        assertThat(reloaded.getAttempts()).isEqualTo(1);
        assertThat(reloaded.getResponseStatus()).isEqualTo(503);
        assertThat(reloaded.getLastError()).isEqualTo("connection refused");
        assertThat(reloaded.getNextAttemptAt()).isEqualTo(nextAttempt);
        assertThat(reloaded.getEventType()).isEqualTo("content.published");
        assertThat(reloaded.getEndpointId()).isEqualTo(endpointId);
        assertThat(reloaded.getEventId()).isEqualTo(eventId);
        // V1_014 added both; a null here means the migration and the mapping disagree.
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
        assertThat(reloaded.getVersion()).isNotNull();
    }

    @Test
    @DisplayName("retrying a delivery advances the version the base entity added")
    void updatingADeliveryAdvancesTheVersion() {
        UUID org = UUID.randomUUID();
        UUID endpointId = asTenant(org, em -> {
            WebhookEndpoint endpoint = WebhookEndpoint.register(org,
                    "https://hooks.acme.example/ver", null, List.of(), "ct", "dek-5");
            em.persist(endpoint);
            return endpoint.getId();
        });
        UUID deliveryId = asTenant(org, em -> {
            WebhookDelivery delivery = WebhookDelivery.pending(org, endpointId,
                    UUID.randomUUID(), "content.published");
            delivery.recordFailure(500, "first attempt", Instant.now().plusSeconds(30), false);
            em.persist(delivery);
            return delivery.getId();
        });

        Long versionAfterInsert = asTenant(org, em ->
                em.find(WebhookDelivery.class, deliveryId).getVersion());

        asTenant(org, em -> {
            WebhookDelivery delivery = em.find(WebhookDelivery.class, deliveryId);
            delivery.recordDelivered(200, Instant.now());
            return null;
        });

        WebhookDelivery afterUpdate = asTenant(org, em -> em.find(WebhookDelivery.class, deliveryId));

        assertThat(afterUpdate.getStatus()).isEqualTo(WebhookDelivery.Status.DELIVERED);
        assertThat(afterUpdate.getAttempts()).isEqualTo(2);
        assertThat(afterUpdate.getResponseStatus()).isEqualTo(200);
        assertThat(afterUpdate.getDeliveredAt()).isNotNull();
        // Optimistic locking on a delivery is what stops a worker that died mid-attempt
        // from having its outcome silently overwritten by the next one.
        assertThat(afterUpdate.getVersion()).isGreaterThan(versionAfterInsert);
        assertThat(afterUpdate.getUpdatedAt()).isNotNull();
        assertThat(afterUpdate.getLastError()).isNull();
    }

    @Test
    @DisplayName("an oversized failure message is truncated rather than failing the insert")
    void anOversizedErrorMessageIsTruncated() {
        UUID org = UUID.randomUUID();
        UUID endpointId = asTenant(org, em -> {
            WebhookEndpoint endpoint = WebhookEndpoint.register(org,
                    "https://hooks.acme.example/long", null, List.of(), "ct", "dek-6");
            em.persist(endpoint);
            return endpoint.getId();
        });
        String longError = "x".repeat(5000);

        UUID deliveryId = asTenant(org, em -> {
            WebhookDelivery delivery = WebhookDelivery.pending(org, endpointId,
                    UUID.randomUUID(), "content.published");
            delivery.recordFailure(0, longError, null, true);
            em.persist(delivery);
            return delivery.getId();
        });

        WebhookDelivery reloaded = asTenant(org, em -> em.find(WebhookDelivery.class, deliveryId));

        // The column is varchar(1000). Without the truncation this insert throws, and a
        // failure to record a failure is the worst possible place to lose information.
        assertThat(reloaded.getLastError()).hasSize(1000);
        assertThat(reloaded.getStatus()).isEqualTo(WebhookDelivery.Status.FAILED);
        assertThat(reloaded.getNextAttemptAt()).isNull();
        // 0 means "no HTTP response at all", which must stay null and not become a 0.
        assertThat(reloaded.getResponseStatus()).isNull();
    }

    @Test
    @DisplayName("a second tenant can see neither the endpoint nor its deliveries")
    void anotherTenantSeesNothing() {
        UUID owner = UUID.randomUUID();
        UUID intruder = UUID.randomUUID();
        UUID endpointId = asTenant(owner, em -> {
            WebhookEndpoint endpoint = WebhookEndpoint.register(owner,
                    "https://hooks.acme.example/secret", null, List.of("content.published"),
                    "ct", "dek-7");
            em.persist(endpoint);
            WebhookDelivery delivery = WebhookDelivery.pending(owner, endpoint.getId(),
                    UUID.randomUUID(), "content.published");
            em.persist(delivery);
            return endpoint.getId();
        });

        assertThat(asTenant(intruder, em -> em.find(WebhookEndpoint.class, endpointId))).isNull();
        assertThat(asTenant(intruder, em -> em.createQuery(
                        "select count(e) from WebhookEndpoint e", Long.class)
                .getSingleResult())).isZero();
        assertThat(asTenant(intruder, em -> em.createQuery(
                        "select count(d) from WebhookDelivery d", Long.class)
                .getSingleResult())).isZero();
    }

    @Test
    @DisplayName("the derived finders parse, execute and stay inside the tenant")
    void theDerivedFindersWorkAndStayScoped() {
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        UUID activeId = asTenant(owner, em -> {
            WebhookEndpoint active = WebhookEndpoint.register(owner,
                    "https://hooks.acme.example/active", "active", List.of("content.published"),
                    "ct", "dek-8");
            WebhookEndpoint paused = WebhookEndpoint.register(owner,
                    "https://hooks.acme.example/paused", "paused", List.of(), "ct", "dek-9");
            paused.pause();
            em.persist(active);
            em.persist(paused);
            return active.getId();
        });
        asTenant(other, em -> {
            em.persist(WebhookEndpoint.register(other,
                    "https://hooks.other.example/x", null, List.of(), "ct", "dek-10"));
            return null;
        });

        asTenant(owner, em -> {
            IntegrationRepositories.WebhookEndpointRepository endpoints =
                    new JpaRepositoryFactory(em).getRepository(
                            IntegrationRepositories.WebhookEndpointRepository.class);

            assertThat(endpoints.findByOrganizationId(owner)).hasSize(2);
            // "ActiveTrue" is derived from the property name alone; a typo here is not a
            // compile error and nothing else in the build would catch it.
            assertThat(endpoints.findByOrganizationIdAndActiveTrue(owner))
                    .hasSize(1)
                    .allSatisfy(e -> assertThat(e.getUrl())
                            .isEqualTo("https://hooks.acme.example/active"));
            assertThat(endpoints.findByIdAndOrganizationId(activeId, owner)).isPresent();
            assertThat(endpoints.findByIdAndOrganizationId(activeId, other)).isEmpty();
            // The other tenant's endpoint must not be reachable even by its own id.
            assertThat(endpoints.findByOrganizationId(other)).isEmpty();
            return null;
        });
    }

    @Test
    @DisplayName("delivery finders count by status within the tenant")
    void theDeliveryFindersWork() {
        UUID org = UUID.randomUUID();
        UUID endpointId = asTenant(org, em -> {
            WebhookEndpoint endpoint = WebhookEndpoint.register(org,
                    "https://hooks.acme.example/count", null, List.of(), "ct", "dek-11");
            em.persist(endpoint);
            return endpoint.getId();
        });

        asTenant(org, em -> {
            for (int i = 0; i < 3; i++) {
                WebhookDelivery pending = WebhookDelivery.pending(org, endpointId,
                        UUID.randomUUID(), "content.published");
                if (i == 0) {
                    pending.recordDelivered(200, Instant.now());
                }
                em.persist(pending);
            }
            return null;
        });

        asTenant(org, em -> {
            IntegrationRepositories.WebhookDeliveryRepository deliveries =
                    new JpaRepositoryFactory(em).getRepository(
                            IntegrationRepositories.WebhookDeliveryRepository.class);

            assertThat(deliveries.findByEndpointIdAndOrganizationId(endpointId, org)).hasSize(3);
            assertThat(deliveries.countByEndpointIdAndOrganizationIdAndStatus(endpointId, org,
                    WebhookDelivery.Status.PENDING)).isEqualTo(2);
            assertThat(deliveries.countByEndpointIdAndOrganizationIdAndStatus(endpointId, org,
                    WebhookDelivery.Status.DELIVERED)).isEqualTo(1);
            return null;
        });
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
        EntityManager em = emf.createEntityManager();
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
     * {@link com.hatis.platform.tenant.TenantIsolationIT} does it: Flyway's classpath
     * scanner resolves nothing under the runner's classloader and then reports success
     * having applied no migrations. Both the module directory and the repository root are
     * tried, because the working directory depends on how the runner was invoked.
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
