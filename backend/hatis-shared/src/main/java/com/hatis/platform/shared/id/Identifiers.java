package com.hatis.platform.shared.id;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Identifier generation and normalisation.
 *
 * <p>The platform never exposes sequential identifiers: they leak tenant volume,
 * invite enumeration and make cross-tenant guessing cheap. Every public
 * identifier is a random UUID (122 bits of entropy).
 */
public final class Identifiers {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$");

    private Identifiers() {
    }

    public static UUID newId() {
        return UUID.randomUUID();
    }

    public static String newIdString() {
        return UUID.randomUUID().toString();
    }

    /** Parses a UUID strictly; throws {@link IllegalArgumentException} on malformed input. */
    public static UUID parse(String raw) {
        if (raw == null || !UUID_PATTERN.matcher(raw).matches()) {
            throw new IllegalArgumentException("Not a valid identifier: " + abbreviate(raw));
        }
        return UUID.fromString(raw.toLowerCase(Locale.ROOT));
    }

    public static boolean isValid(String raw) {
        return raw != null && UUID_PATTERN.matcher(raw).matches();
    }

    /**
     * Builds a URL-safe slug from arbitrary text. Returns {@code null} when no
     * usable characters remain, so callers must decide on a fallback.
     */
    public static String slugify(String text) {
        if (text == null) {
            return null;
        }
        String slug = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (slug.length() > 63) {
            slug = slug.substring(0, 63).replaceAll("-+$", "");
        }
        return slug.isEmpty() ? null : slug;
    }

    public static boolean isValidSlug(String slug) {
        return slug != null && SLUG_PATTERN.matcher(slug).matches();
    }

    /** Truncates a value for safe use in log messages; never logs a full secret-shaped value. */
    private static String abbreviate(String raw) {
        if (raw == null) {
            return "null";
        }
        return raw.length() <= 12 ? "***" : raw.substring(0, 4) + "***";
    }
}
