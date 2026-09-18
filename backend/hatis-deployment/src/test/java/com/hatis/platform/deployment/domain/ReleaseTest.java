package com.hatis.platform.deployment.domain;

import com.hatis.platform.shared.error.PlatformExceptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Release invariants.
 *
 * <p>A release is what a rollback restores, so it must name bytes that cannot change
 * underneath it. Every test here protects that property.
 */
class ReleaseTest {

    private Release newRelease(String image, String digest) {
        return new Release(UUID.randomUUID(), UUID.randomUUID(), "1.4.2", image, digest,
                Release.SourceType.GITHUB, "main", "abc123", UUID.randomUUID());
    }

    @Test
    @DisplayName("an image tagged latest is not deployable")
    void rejectsLatestTag() {
        assertThatThrownBy(() -> newRelease("registry.example.com/app:latest", null))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("latest");
    }

    @Test
    @DisplayName("an untagged image without a digest is not deployable")
    void rejectsUnpinnedImage() {
        assertThatThrownBy(() -> newRelease("registry.example.com/app", null))
                .isInstanceOf(PlatformExceptions.Validation.class);
    }

    @Test
    @DisplayName("a pinned tag is accepted")
    void acceptsPinnedTag() {
        assertThat(newRelease("registry.example.com/app:1.4.2", null).getImage())
                .isEqualTo("registry.example.com/app:1.4.2");
    }

    @Test
    @DisplayName("a digest-pinned image is accepted")
    void acceptsDigest() {
        Release release = newRelease("registry.example.com/app",
                "sha256:" + "b".repeat(64));

        assertThat(release.getDigest()).startsWith("sha256:");
    }

    @Test
    @DisplayName("a new release starts unscanned and therefore undeployable")
    void startsUndeployable() {
        Release release = newRelease("registry.example.com/app:1.4.2", null);

        assertThat(release.getStatus()).isEqualTo(Release.Status.CREATED);
        assertThat(release.getScanStatus()).isEqualTo(Release.ScanStatus.PENDING);
        assertThat(release.isDeployable()).isFalse();
        assertThatThrownBy(release::requireDeployable)
                .isInstanceOf(PlatformExceptions.StateConflict.class);
    }

    @Test
    @DisplayName("a passing scan makes the release deployable")
    void passingScanEnablesDeployment() {
        Release release = newRelease("registry.example.com/app:1.4.2", null);
        release.recordScan(Release.ScanStatus.PASSED, "no findings");

        assertThat(release.isDeployable()).isTrue();
        release.requireDeployable();
    }

    @Test
    @DisplayName("a failing scan rejects the release")
    void failingScanRejectsRelease() {
        Release release = newRelease("registry.example.com/app:1.4.2", null);
        release.recordScan(Release.ScanStatus.FAILED, "CVE-2024-0001 critical");

        assertThat(release.getStatus()).isEqualTo(Release.Status.REJECTED);
        assertThat(release.isDeployable()).isFalse();
    }

    @Test
    @DisplayName("a superseded release cannot be deployed again")
    void supersededReleaseIsNotDeployable() {
        Release release = newRelease("registry.example.com/app:1.4.2", null);
        release.recordScan(Release.ScanStatus.PASSED, null);
        release.supersede();

        assertThat(release.isDeployable()).isFalse();
    }

    @Test
    @DisplayName("the version is normalised so lookups are stable")
    void normalizesVersion() {
        assertThat(newRelease("registry.example.com/app:1.4.2", null).getReleaseVersion()).isEqualTo("1.4.2");
    }
}
