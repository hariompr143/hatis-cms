package com.hatis.platform.integration.domain;

import com.hatis.platform.shared.error.PlatformExceptions;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Decides whether the platform may make an outbound request to a customer-supplied URL.
 *
 * <p>Outbound webhooks turn the platform into a client of an address the tenant chose,
 * which makes this an SSRF boundary rather than ordinary input validation. Without it a
 * tenant could register {@code http://169.254.169.254/latest/meta-data/iam/} and have the
 * platform fetch its own cloud credentials, or reach a database on the private network
 * that is deliberately unreachable from outside.
 *
 * <h2>Why hostname checks alone are not enough</h2>
 *
 * A tenant controls their own DNS, so {@code webhook.evil.example} can resolve to
 * {@code 127.0.0.1} or to {@code 169.254.169.254}. Rejecting the literal string
 * "localhost" therefore proves nothing. {@link #assertDeliverable} resolves the host and
 * rejects the delivery if <em>any</em> resolved address is loopback, link-local,
 * site-local or otherwise non-global.
 *
 * <p>That still leaves a gap worth naming: resolution here and resolution at send time
 * are two lookups, and a hostile authoritative server can return a public address first
 * and an internal one second — a DNS rebinding attack. Closing it properly means
 * resolving once and connecting to that pinned address with the original Host header,
 * which is a transport concern; {@code WebhookHttpSender} is where that has to happen.
 * This class is the first line, not the whole defence.
 */
public final class WebhookUrlValidator {

    /** Hostname suffixes that only ever resolve inside the cluster or the machine. */
    private static final List<String> BLOCKED_SUFFIXES = List.of(
            ".local", ".localhost", ".internal", ".internal.", ".cluster.local",
            ".svc", ".svc.cluster.local", ".lan", ".corp", ".intranet");

    private WebhookUrlValidator() {
    }

    /**
     * Validates a URL at registration time.
     *
     * <p>Deliberately does not resolve DNS: registration happens in a user request and a
     * slow or hostile resolver must not be able to hold that request open. Resolution is
     * checked per delivery, in {@link #assertDeliverable}.
     *
     * @throws PlatformExceptions.Validation when the URL cannot be a legitimate webhook target
     */
    public static URI validate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw invalid("A webhook URL is required", "url");
        }
        URI uri;
        try {
            uri = new URI(raw.trim());
        } catch (URISyntaxException e) {
            throw invalid("That is not a valid URL", "url");
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        // Anything but http and https is refused outright: file, gopher, jar and dict
        // have all been used to turn a URL-fetching service into a local file reader.
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw invalid("Only http and https webhook URLs are accepted", "url");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw invalid("The webhook URL must include a host", "url");
        }
        // Credentials in a URL are a smuggling vector and are never needed here.
        if (uri.getUserInfo() != null) {
            throw invalid("The webhook URL must not contain credentials", "url");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        for (String suffix : BLOCKED_SUFFIXES) {
            if (host.endsWith(suffix)) {
                throw invalid("Webhook URLs may not point at internal or cluster-local hosts", "url");
            }
        }
        if (host.equals("localhost")) {
            throw invalid("Webhook URLs may not point at localhost", "url");
        }
        // An IP literal can be checked now, without resolving anything.
        if (isIpLiteral(host)) {
            assertAddressIsPublic(parseLiteral(host), "url");
        }
        return uri;
    }

    /**
     * Re-checks a previously validated URL immediately before a delivery, resolving DNS.
     *
     * @throws PlatformExceptions.BusinessRuleViolation when the host resolves to a
     *                                                  non-public address
     */
    public static void assertDeliverable(String raw) {
        resolveDeliverable(raw);
    }

    /**
     * Resolves the host once, refuses any non-public address, and returns the addresses
     * that passed.
     *
     * <p>Callers that are about to open a connection must use this rather than
     * {@link #assertDeliverable} followed by their own lookup. Two resolutions is exactly
     * the window a DNS rebinding attack needs: the first answer is public and gets
     * approved, the second points at the metadata endpoint and gets connected to. One
     * lookup, vetted, and then connected to <em>by address</em> leaves no window at all.
     *
     * @return the vetted addresses, never empty
     * @throws PlatformExceptions.BusinessRuleViolation when the host is unresolvable or
     *                                                  resolves to a non-public address
     */
    public static List<InetAddress> resolveDeliverable(String raw) {
        URI uri;
        try {
            uri = new URI(raw);
        } catch (URISyntaxException e) {
            throw new PlatformExceptions.BusinessRuleViolation("The stored webhook URL is not valid");
        }
        String host = uri.getHost();
        if (host == null) {
            throw new PlatformExceptions.BusinessRuleViolation("The stored webhook URL has no host");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new PlatformExceptions.BusinessRuleViolation(
                    "The webhook host could not be resolved: " + host);
        }
        if (addresses.length == 0) {
            throw new PlatformExceptions.BusinessRuleViolation(
                    "The webhook host resolved to no addresses: " + host);
        }
        // Every address must be public. Checking only the first would let a host with
        // one public A record and one internal A record through, and the client could
        // then pick either.
        for (InetAddress address : addresses) {
            String reason = reasonNotPublic(address);
            if (reason != null) {
                // BusinessRuleViolation, not Validation. The URL was already accepted and
                // stored; this fires from the dispatcher, not from a request, so a 400-style
                // "you sent something malformed" would misclassify a delivery failure — and
                // the dispatcher needs to treat a permanent refusal differently from a
                // timeout it should retry. The address itself is still not echoed: it
                // describes our network layout, which the tenant does not need.
                throw new PlatformExceptions.BusinessRuleViolation(
                        "The webhook host resolves to " + reason
                                + ", which the platform is not permitted to contact");
            }
        }
        return List.of(addresses);
    }

    private static void assertAddressIsPublic(InetAddress address, String field) {
        String reason = reasonNotPublic(address);
        if (reason != null) {
            throw invalid("Webhook URLs may not point at " + reason, field);
        }
    }

    /** Returns why an address is not usable, or null when it is. */
    private static String reasonNotPublic(InetAddress address) {
        if (address.isLoopbackAddress()) {
            return "loopback addresses";
        }
        if (address.isAnyLocalAddress()) {
            return "wildcard addresses";
        }
        if (address.isLinkLocalAddress()) {
            // Includes 169.254.169.254, the cloud metadata endpoint.
            return "link-local addresses";
        }
        if (address.isSiteLocalAddress()) {
            // 10/8, 172.16/12 and 192.168/16.
            return "private network addresses";
        }
        if (address.isMulticastAddress()) {
            return "multicast addresses";
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            // 100.64/10 carrier-grade NAT, and 192.0.0/24 and 198.18/15 benchmarking.
            if (first == 100 && second >= 64 && second <= 127) {
                return "carrier-grade NAT addresses";
            }
            if (first == 198 && (second == 18 || second == 19)) {
                return "benchmarking addresses";
            }
        }
        return null;
    }

    /** Dotted-quad form. Recognised syntactically, never by asking a resolver. */
    private static final Pattern IPV4 = Pattern.compile(
            "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)"
                    + "(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");

    /**
     * True when the host is written as an address rather than a name.
     *
     * <p>This must not call a resolver. {@code InetAddress.getByName} looks a name up if
     * it is not already an address, which would put a DNS query — and a hostile or slow
     * resolver — into the registration request. Recognising the two literal forms
     * syntactically keeps registration free of network calls.
     */
    private static boolean isIpLiteral(String host) {
        // A colon can only appear in an IPv6 literal, which URLs carry in square brackets.
        return host.indexOf(':') >= 0 || IPV4.matcher(host).matches();
    }

    private static InetAddress parseLiteral(String host) {
        try {
            return InetAddress.getByName(host.replace("[", "").replace("]", ""));
        } catch (UnknownHostException e) {
            throw invalid("The webhook URL contains an invalid address", "url");
        }
    }

    private static PlatformExceptions.Validation invalid(String message, String field) {
        return new PlatformExceptions.Validation(message, Map.of("field", field));
    }
}
