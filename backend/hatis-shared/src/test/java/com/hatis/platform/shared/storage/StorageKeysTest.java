package com.hatis.platform.shared.storage;

import com.hatis.platform.shared.error.PlatformExceptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tenant-scoped storage keys.
 *
 * <p>These are the tests that matter most in the storage layer: if a caller can
 * build a key outside its own prefix, tenant isolation in object storage is gone.
 */
class StorageKeysTest {

    private final UUID organizationId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID assetId = UUID.randomUUID();

    @Test
    @DisplayName("every generated key starts with the tenant prefix")
    void keysAreTenantScoped() {
        String prefix = StorageKeys.tenantPrefix(organizationId);

        assertThat(StorageKeys.asset(organizationId, projectId, assetId, 1, "a.png")).startsWith(prefix);
        assertThat(StorageKeys.rendition(organizationId, projectId, assetId, "thumb")).startsWith(prefix);
        assertThat(StorageKeys.contentMedia(organizationId, projectId, assetId, 1)).startsWith(prefix);
        assertThat(StorageKeys.export(organizationId, assetId, "zip")).startsWith(prefix);
    }

    @Test
    @DisplayName("a key from another tenant is rejected")
    void rejectsForeignKey() {
        UUID other = UUID.randomUUID();
        String foreign = StorageKeys.asset(other, projectId, assetId, 1, "a.png");

        assertThatThrownBy(() -> StorageKeys.requireTenantPrefix(organizationId, foreign))
                .isInstanceOf(PlatformExceptions.Forbidden.class);
    }

    @Test
    @DisplayName("path traversal inside a key is rejected")
    void rejectsTraversal() {
        String traversal = StorageKeys.tenantPrefix(organizationId) + "../../" + other() + "/secret";

        assertThatThrownBy(() -> StorageKeys.requireTenantPrefix(organizationId, traversal))
                .isInstanceOf(PlatformExceptions.Forbidden.class);
    }

    @Test
    @DisplayName("a null key is rejected rather than treated as unscoped")
    void rejectsNullKey() {
        assertThatThrownBy(() -> StorageKeys.requireTenantPrefix(organizationId, null))
                .isInstanceOf(PlatformExceptions.Forbidden.class);
    }

    @Test
    @DisplayName("a key inside the tenant prefix is accepted")
    void acceptsOwnKey() {
        String own = StorageKeys.asset(organizationId, projectId, assetId, 1, "a.png");

        assertThatCode(() -> StorageKeys.requireTenantPrefix(organizationId, own)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a hostile filename is reduced to one safe path segment")
    void sanitizesFilename() {
        assertThat(StorageKeys.sanitize("../../etc/passwd")).isEqualTo("passwd");
        assertThat(StorageKeys.sanitize("a\\b\\c.txt")).isEqualTo("c.txt");
        assertThat(StorageKeys.sanitize("<script>.png")).isEqualTo("_script_.png");
        // A leading dot is prefixed rather than replaced: the rule exists so a
        // filename cannot become a dotfile such as .htaccess when it lands on disk.
        assertThat(StorageKeys.sanitize(".hidden")).isEqualTo("_.hidden");
        assertThat(StorageKeys.sanitize(".htaccess")).isEqualTo("_.htaccess");
        assertThat(StorageKeys.sanitize(null)).isEqualTo("file");
        assertThat(StorageKeys.sanitize("   ")).isEqualTo("file");
    }

    @Test
    @DisplayName("a long filename is truncated from the front so the extension survives")
    void truncatesLongFilename() {
        String longName = "a".repeat(300) + ".png";

        assertThat(StorageKeys.sanitize(longName)).hasSize(120).endsWith(".png");
    }

    private static String other() {
        return UUID.randomUUID().toString();
    }
}
