package com.hatis.platform.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Module and layer boundaries, enforced at build time.
 *
 * <p>These rules exist because a modular monolith only stays modular if something
 * refuses to compile when a boundary is crossed. Review does not scale to that; a
 * failing test does. Each rule below corresponds to a promise made in
 * {@code docs/architecture/04-module-service-boundaries.md} — the promise that each
 * of these packages could later be extracted into its own service.
 */
@DisplayName("Hexagonal architecture and bounded-context boundaries")
class HexagonalArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.hatis.platform");
    }

    @Test
    @DisplayName("the domain layer carries no framework dependencies")
    void domainIsFrameworkFree() {
        ArchRule rule = noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "org.hibernate..",
                        "com.fasterxml.jackson..",
                        "..adapter..");

        rule.because("the domain must be portable to another framework or process, "
                + "so it may only use the JDK, jakarta.persistence and the shared kernel")
                .check(classes);
    }

    @Test
    @DisplayName("REST adapters call application services, never repositories")
    void restAdaptersGoThroughApplicationServices() {
        ArchRule rule = noClasses().that().resideInAPackage("..adapter.rest..")
                .should().dependOnClassesThat().resideInAPackage("..adapter.persistence..");

        rule.because("a controller that reaches a repository bypasses authorization, "
                + "quota checks and the transaction boundary")
                .check(classes);
    }

    @Test
    @DisplayName("outbound ports live in port.out and are implemented by adapters")
    void portsAreInboundOnly() {
        ArchRule rule = noClasses().that().resideInAPackage("..port.out..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..adapter..",
                        "org.springframework.web..",
                        "software.amazon.awssdk..");

        rule.because("a port must be implementable without pulling in the adapter it abstracts")
                .check(classes);
    }

    @Test
    @DisplayName("bounded contexts have no dependency cycles")
    void contextsAreAcyclic() {
        slices().matching("com.hatis.platform.(*)..")
                .should().beFreeOfCycles()
                .because("a cycle between contexts means neither can be extracted or deployed alone")
                .check(classes);
    }

    @Test
    @DisplayName("every bounded context keeps the same internal shape")
    void contextsFollowTheSameLayout() {
        ArchRule rule = classes().that().resideInAPackage("com.hatis.platform..")
                // The root package holds the Spring Boot composition root, which wires
                // the contexts together and belongs to none of them. Matched without
                // the ".." suffix so only that exact package is exempt, not the tree.
                .and().resideOutsideOfPackages("com.hatis.platform.shared..",
                        "com.hatis.platform.architecture..",
                        "com.hatis.platform.security..",
                        "com.hatis.platform")
                .should().resideInAnyPackage(
                        "com.hatis.platform..domain..",
                        "com.hatis.platform..application..",
                        "com.hatis.platform..port.out..",
                        "com.hatis.platform..adapter.persistence..",
                        "com.hatis.platform..adapter.rest..",
                        "com.hatis.platform..adapter..");

        rule.because("a context that invents its own layout cannot be reviewed or "
                + "extracted by the same rules as the others")
                .check(classes);
    }

    @Test
    @DisplayName("only the shared kernel may define cross-context types")
    void sharedKernelIsTheOnlyCommonDependency() {
        ArchRule rule = noClasses().that().resideInAPackage("com.hatis.platform.shared..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("com.hatis.platform.cms..",
                        "com.hatis.platform.assets..",
                        "com.hatis.platform.deployment..",
                        "com.hatis.platform.domains..",
                        "com.hatis.platform.billing..",
                        "com.hatis.platform.identity..",
                        "com.hatis.platform.organization..",
                        "com.hatis.platform.authorization..");

        rule.because("a kernel that imports a context makes every module depend on that context")
                .check(classes);
    }

    @Test
    @DisplayName("tenant-scoped entities extend the tenant-scoped base type")
    void tenantScopedEntitiesUseTheBaseType() {
        // Platform-wide aggregates, plus catalogue rows with a nullable organization.
        //
        // The first group deliberately carries no organization at all. The second —
        // Permission and Role on auth_permissions/auth_roles, and WorkflowDefinition on
        // wf_definitions — is a table that holds both kinds of row: a template with
        // organization_id null that every tenant reads, and tenant-owned rows for the
        // tenants that have them. TenantScopedEntity refuses to exist without an
        // organization, and it is right to: it exists to stop a tenant-owned aggregate
        // forgetting its tenant. A catalogue row is a different thing, which is why the
        // exemption is by name and why each name here has a nullable organization_id in
        // the migration that creates it. V1_013 completes the argument on the database
        // side: it widens read policies to include the null-organization rows and revokes
        // insert, update and delete from hatis_app, so these rows are maintained by
        // migrations and cannot be created by a request.
        String platformWideEntities =
                ".*[.$](User|RefreshToken|MfaEnrolment|AuditLog|Operation|OutboxEntry"
                        + "|IdempotencyRecord|Permission|Role|WorkflowDefinition)";

        ArchRule rule = classes().that().areAnnotatedWith(jakarta.persistence.Entity.class)
                // haveNameNotMatching rather than haveSimpleNameNotIn: the latter has
                // no String... overload in ArchUnit 1.3.0. The pattern is matched
                // against the whole qualified name. The separator class is [.$] and
                // not \. because Permission and Role are nested inside
                // AuthorizationEntities, and matching only on '.' silently let both
                // through as apparent violations.
                .and().haveNameNotMatching(platformWideEntities)
                .should().beAssignableTo(com.hatis.platform.shared.persistence.TenantScopedEntity.class);

        rule.because("a tenant-owned aggregate that is not tenant-scoped in the type system "
                + "is one forgotten predicate away from leaking across tenants")
                .check(classes);
    }
}
