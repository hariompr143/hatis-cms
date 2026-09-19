package com.hatis.platform.integration.domain;

import com.hatis.platform.shared.error.PlatformExceptions;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The subset of a GitHub {@code push} payload the platform acts on.
 *
 * <p>Parsed from an already signature-verified body, but still treated as hostile:
 * every field is validated, and anything unexpected is a rejected request rather
 * than a null further down the stack. GitHub owns this schema and changes it, so
 * the parse is the boundary that keeps their shape out of the domain.
 *
 * @param ref        the full ref, e.g. {@code refs/heads/main}
 * @param before     the previous head SHA; all zeros on branch creation
 * @param after      the new head SHA
 * @param repository the {@code owner/name} the push landed in
 * @param pusher     the GitHub login that pushed
 * @param commitCount how many commits this delivery carried
 * @param created    true when the ref did not exist before
 */
public record GitHubPushEvent(
        String ref,
        String before,
        String after,
        String repository,
        String pusher,
        int commitCount,
        boolean created) {

    /** SHA GitHub sends in {@code before} when a branch is created. */
    public static final String ZERO_SHA = "0".repeat(40);

    /**
     * Parses a verified push payload.
     *
     * @throws PlatformExceptions.MalformedRequest when a required field is missing or
     *                                             of the wrong shape
     */
    public static GitHubPushEvent parse(Map<String, Object> payload) {
        Objects.requireNonNull(payload, "payload");
        String ref = requireText(payload, "ref");
        String before = requireText(payload, "before");
        String after = requireText(payload, "after");
        if (!isSha(before) || !isSha(after)) {
            throw new PlatformExceptions.MalformedRequest(
                    "before and after must be 40 character commit SHAs");
        }
        Object repositoryNode = payload.get("repository");
        if (!(repositoryNode instanceof Map<?, ?> repositoryMap)) {
            throw new PlatformExceptions.MalformedRequest("repository must be an object");
        }
        String fullName = text(repositoryMap, "full_name");
        if (fullName == null || fullName.isBlank()) {
            throw new PlatformExceptions.MalformedRequest("repository.full_name is required");
        }
        String pusher = payload.get("pusher") instanceof Map<?, ?> pusherMap
                ? text(pusherMap, "name")
                : null;
        int commits = payload.get("commits") instanceof List<?> list ? list.size() : 0;
        return new GitHubPushEvent(ref, before, after, fullName, pusher, commits,
                ZERO_SHA.equals(before));
    }

    /** The branch name without the {@code refs/heads/} prefix, or null for tags. */
    public String branch() {
        return ref.startsWith("refs/heads/") ? ref.substring("refs/heads/".length()) : null;
    }

    public boolean isBranchPush() {
        return ref.startsWith("refs/heads/");
    }

    private static boolean isSha(String value) {
        if (value == null || value.length() != 40) {
            return false;
        }
        for (int i = 0; i < 40; i++) {
            if (Character.digit(value.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static String requireText(Map<String, Object> payload, String field) {
        String value = text(payload, field);
        if (value == null || value.isBlank()) {
            throw new PlatformExceptions.MalformedRequest(field + " is required");
        }
        return value;
    }

    private static String text(Map<?, ?> map, String key) {
        return map.get(key) instanceof String s ? s : null;
    }
}
