package com.hatis.platform.analytics.adapter.persistence;

import com.hatis.platform.shared.error.PlatformExceptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A saved dashboard.
 *
 * <p>The layout is a {@code jsonb} column held as text, so the entity's job is narrower than it
 * looks: the document itself is the console's business, but the column is {@code not null} and
 * an empty string is not a layout, and saying so here produces a message a customer can act on
 * instead of a driver error from a failed insert.
 */
@DisplayName("Dashboard")
class DashboardTest {

    private final UUID organizationId = UUID.randomUUID();
    private final UUID principalId = UUID.randomUUID();

    @Test
    @DisplayName("a dashboard keeps its name, project and creator and defaults to private")
    void aDashboardKeepsWhatItWasGiven() {
        UUID projectId = UUID.randomUUID();

        Dashboard dashboard = new Dashboard(organizationId, projectId, "  Operations  ",
                "{\"widgets\":[]}", null, principalId);

        assertThat(dashboard.getName()).isEqualTo("Operations");
        assertThat(dashboard.getProjectId()).isEqualTo(projectId);
        assertThat(dashboard.getCreatedBy()).isEqualTo(principalId);
        assertThat(dashboard.getVisibility()).isEqualTo(Dashboard.Visibility.PRIVATE);
        assertThat(dashboard.getLayout()).isEqualTo("{\"widgets\":[]}");
        assertThat(dashboard.getOrganizationId()).isEqualTo(organizationId);
    }

    @Test
    @DisplayName("a dashboard needs a name of at most the column's width")
    void theNameIsBounded() {
        assertThatThrownBy(() -> dashboard("  ", "{\"widgets\":[]}"))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("name");
        assertThatThrownBy(() -> dashboard("n".repeat(201), "{\"widgets\":[]}"))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("200");
    }

    @Test
    @DisplayName("a dashboard needs a layout, before anything tries to store it")
    void theLayoutIsRequired() {
        assertThatThrownBy(() -> dashboard("Operations", null))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("layout");
        assertThatThrownBy(() -> dashboard("Operations", "   "))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("layout");
    }

    @Test
    @DisplayName("renaming and relaying out are edits, and a null visibility changes nothing")
    void editsReplaceWhatTheyName() {
        Dashboard dashboard = dashboard("Operations", "{\"widgets\":[]}");

        dashboard.rename("  Publishing  ");
        dashboard.changeLayout("{\"widgets\":[{},{}]}");
        dashboard.changeVisibility(null);

        assertThat(dashboard.getName()).isEqualTo("Publishing");
        assertThat(dashboard.getLayout()).isEqualTo("{\"widgets\":[{},{}]}");
        assertThat(dashboard.getVisibility()).isEqualTo(Dashboard.Visibility.PRIVATE);

        dashboard.changeVisibility(Dashboard.Visibility.ORGANIZATION);

        assertThat(dashboard.getVisibility()).isEqualTo(Dashboard.Visibility.ORGANIZATION);

        assertThatThrownBy(() -> dashboard.changeLayout("  "))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("layout");
    }

    private Dashboard dashboard(String name, String layout) {
        return new Dashboard(organizationId, null, name, layout, null, principalId);
    }
}
