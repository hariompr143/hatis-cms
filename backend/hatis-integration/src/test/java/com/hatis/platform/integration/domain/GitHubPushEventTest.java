package com.hatis.platform.integration.domain;

import com.hatis.platform.shared.error.PlatformExceptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Parsing of GitHub's push payload.
 *
 * <p>The body is signature-verified before it reaches here, but it is still an
 * external schema the platform does not control. These tests cover the shapes that
 * would otherwise surface as a null deep inside a transaction.
 */
class GitHubPushEventTest {

    private static final String OLD_SHA = "1".repeat(40);
    private static final String NEW_SHA = "a".repeat(40);

    private static Map<String, Object> validPush() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("ref", "refs/heads/main");
        payload.put("before", OLD_SHA);
        payload.put("after", NEW_SHA);
        payload.put("repository", Map.of("full_name", "acme/shopfront"));
        payload.put("pusher", Map.of("name", "octocat"));
        payload.put("commits", List.of(Map.of(), Map.of(), Map.of()));
        return payload;
    }

    @Test
    @DisplayName("a push to a branch is parsed")
    void parsesABranchPush() {
        GitHubPushEvent event = GitHubPushEvent.parse(validPush());

        assertThat(event.ref()).isEqualTo("refs/heads/main");
        assertThat(event.branch()).isEqualTo("main");
        assertThat(event.isBranchPush()).isTrue();
        assertThat(event.before()).isEqualTo(OLD_SHA);
        assertThat(event.after()).isEqualTo(NEW_SHA);
        assertThat(event.repository()).isEqualTo("acme/shopfront");
        assertThat(event.pusher()).isEqualTo("octocat");
        assertThat(event.commitCount()).isEqualTo(3);
        assertThat(event.created()).isFalse();
    }

    @Test
    @DisplayName("a zero before-SHA means the branch was created")
    void recognisesBranchCreation() {
        Map<String, Object> payload = validPush();
        payload.put("before", GitHubPushEvent.ZERO_SHA);

        GitHubPushEvent event = GitHubPushEvent.parse(payload);

        assertThat(event.created()).isTrue();
    }

    @Test
    @DisplayName("a tag push has no branch name")
    void tagPushHasNoBranch() {
        Map<String, Object> payload = validPush();
        payload.put("ref", "refs/tags/v1.2.0");

        GitHubPushEvent event = GitHubPushEvent.parse(payload);

        assertThat(event.isBranchPush()).isFalse();
        assertThat(event.branch()).isNull();
    }

    @Test
    @DisplayName("a missing pusher is tolerated; GitHub omits it for some apps")
    void toleratesMissingPusher() {
        Map<String, Object> payload = validPush();
        payload.remove("pusher");

        assertThat(GitHubPushEvent.parse(payload).pusher()).isNull();
    }

    @Test
    @DisplayName("a missing commits array counts as zero rather than failing")
    void toleratesMissingCommits() {
        Map<String, Object> payload = validPush();
        payload.remove("commits");

        assertThat(GitHubPushEvent.parse(payload).commitCount()).isZero();
    }

    @Test
    @DisplayName("a short or non-hex SHA is rejected")
    void rejectsBadShas() {
        Map<String, Object> tooShort = validPush();
        tooShort.put("after", "abc123");
        assertThatThrownBy(() -> GitHubPushEvent.parse(tooShort))
                .isInstanceOf(PlatformExceptions.MalformedRequest.class);

        Map<String, Object> notHex = validPush();
        notHex.put("after", "z".repeat(40));
        assertThatThrownBy(() -> GitHubPushEvent.parse(notHex))
                .isInstanceOf(PlatformExceptions.MalformedRequest.class);
    }

    @Test
    @DisplayName("missing required fields are rejected with the field named")
    void rejectsMissingFields() {
        for (String field : List.of("ref", "before", "after")) {
            Map<String, Object> payload = validPush();
            payload.remove(field);
            assertThatThrownBy(() -> GitHubPushEvent.parse(payload))
                    .isInstanceOf(PlatformExceptions.MalformedRequest.class)
                    .hasMessageContaining(field);
        }
    }

    @Test
    @DisplayName("a repository that is not an object is rejected")
    void rejectsBadRepository() {
        Map<String, Object> payload = validPush();
        payload.put("repository", "acme/shopfront");

        assertThatThrownBy(() -> GitHubPushEvent.parse(payload))
                .isInstanceOf(PlatformExceptions.MalformedRequest.class);
    }

    @Test
    @DisplayName("a repository without a full_name is rejected")
    void rejectsRepositoryWithoutName() {
        Map<String, Object> payload = validPush();
        payload.put("repository", Map.of("name", "shopfront"));

        assertThatThrownBy(() -> GitHubPushEvent.parse(payload))
                .isInstanceOf(PlatformExceptions.MalformedRequest.class)
                .hasMessageContaining("full_name");
    }
}
