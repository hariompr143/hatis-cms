package com.hatis.platform.shared.secret;

/**
 * A pointer to a secret held outside the platform database.
 *
 * <p>Storing references rather than material means the platform database is not a
 * secret store, and that rotation happens in one place (the provider) instead of
 * in every row that copied a value.
 */
public record SecretRef(String provider, String path, String version) {

    public static SecretRef of(String provider, String path) {
        return new SecretRef(provider, path, null);
    }

    /** Safe to log and to return from APIs: contains no secret material. */
    @Override
    public String toString() {
        return provider + "://" + path + (version == null ? "" : "@" + version);
    }
}
