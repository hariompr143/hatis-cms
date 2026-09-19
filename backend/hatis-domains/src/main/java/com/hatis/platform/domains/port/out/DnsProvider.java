package com.hatis.platform.domains.port.out;

import java.util.List;

/**
 * Authoritative DNS for a customer's apex domain.
 *
 * <p>Two kinds of adapter exist and they differ in what they can do:
 *
 * <ul>
 *   <li><b>Managed zones</b> (for example a Cloudflare zone the platform holds
 *       credentials for) can write records, so {@link #manages(String)} returns true
 *       and the platform publishes the CNAME itself.</li>
 *   <li><b>Read-only resolvers</b> can only answer lookups. They cannot write, and
 *       must say so through {@link #manages(String)} returning false rather than
 *       pretending to have published a record.</li>
 * </ul>
 *
 * <p>Write methods must throw {@code PlatformExceptions.DependencyUnavailable} when
 * the provider cannot be reached, so the caller can leave the domain in a degraded
 * state instead of marking it live.
 */
public interface DnsProvider {

    /** Stable identifier for this provider, stored on the domain row. */
    String name();

    /** Whether this provider is authoritative for {@code apexDomain} and can write to it. */
    boolean manages(String apexDomain);

    /** Creates or replaces a single record, so re-verification is idempotent. */
    void upsertRecord(String apexDomain, String recordName, RecordType type, String value, long ttl);

    /** Removes a record; absent records are not an error. */
    void deleteRecord(String apexDomain, String recordName, RecordType type);

    /** Current values published for the name, empty when the name does not resolve. */
    List<String> lookup(String recordName, RecordType type);

    /**
     * Whether the published records already carry {@code expected}.
     *
     * <p>Derived from {@link #lookup(String, RecordType)} so read-only resolvers can
     * verify a domain without implementing a second code path. Quoted TXT values are
     * accepted: resolvers disagree about whether the surrounding quotes are part of
     * the value, and rejecting on that alone would fail verification for a domain
     * that is in fact correctly configured.
     */
    default boolean recordMatches(String recordName, RecordType type, String expected) {
        if (expected == null) {
            return false;
        }
        String wanted = expected.trim();
        return lookup(recordName, type).stream()
                .filter(java.util.Objects::nonNull)
                .map(String::trim)
                .anyMatch(value -> value.equals(wanted) || value.equals("\"" + wanted + "\""));
    }

    /** The record types the platform publishes. */
    enum RecordType {
        /** Domain ownership challenge. */
        TXT,
        /** Points a customer hostname at the platform ingress. */
        CNAME
    }
}
