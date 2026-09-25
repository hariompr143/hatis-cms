package com.hatis.platform.workflow.domain;

import com.hatis.platform.shared.id.Identifiers;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.UUID;

/**
 * Builds {@link WorkflowDefinition} rows for tests.
 *
 * <h2>Why this class is in the entity's own package</h2>
 *
 * {@code wf_definitions} is a catalogue table: rows are inserted by migrations and read by
 * the engine, and {@code V1_013} revokes {@code insert}, {@code update} and {@code delete}
 * on it from the application role. The entity is therefore shaped like the table — no
 * creation constructor, and a {@code protected} no-argument constructor for JPA — which is
 * correct for production and inconvenient for a test that needs a definition to run
 * against. Living in the same package lets this fixture call that constructor directly;
 * the private fields are then set with {@link ReflectionTestUtils}, which is Spring's
 * supported mechanism and fails loudly if a field is renamed.
 *
 * <p>The alternative is worse rather than more honest: adding a public constructor or
 * setters to the entity so that tests can build one would give every caller in the
 * application the ability to fabricate a definition the database refuses to store. One
 * reflection call confined to a test fixture is the smaller price, and it is paid once
 * here rather than in each test.
 */
public final class WorkflowFixtures {

    private WorkflowFixtures() {
    }

    /** A tenant-owned definition. */
    public static WorkflowDefinition definition(UUID organizationId, String key, int version, String json) {
        WorkflowDefinition definition = new WorkflowDefinition();
        ReflectionTestUtils.setField(definition, "id", Identifiers.newId());
        ReflectionTestUtils.setField(definition, "organizationId", organizationId);
        ReflectionTestUtils.setField(definition, "key", key);
        ReflectionTestUtils.setField(definition, "name", key);
        ReflectionTestUtils.setField(definition, "version", version);
        ReflectionTestUtils.setField(definition, "definition", json);
        ReflectionTestUtils.setField(definition, "status", WorkflowDefinition.Status.ACTIVE);
        ReflectionTestUtils.setField(definition, "createdAt", Instant.now());
        ReflectionTestUtils.setField(definition, "updatedAt", Instant.now());
        return definition;
    }

    /** A platform template: no organization, so every tenant reads the same row. */
    public static WorkflowDefinition template(String key, int version, String json) {
        return definition(null, key, version, json);
    }
}
