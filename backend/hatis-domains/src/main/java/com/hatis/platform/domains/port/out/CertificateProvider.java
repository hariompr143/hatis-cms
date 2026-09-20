package com.hatis.platform.domains.port.out;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * TLS certificate issuance for a customer hostname.
 *
 * <p>A certificate is requested only after the domain has passed live DNS
 * verification, because issuance without proof of control invites a CA to hand out a
 * certificate for a host the tenant does not own.
 *
 * <p>Private key material never crosses this interface: adapters reference the
 * Kubernetes Secret that holds the key pair and return only its name. Nothing here
 * returns, logs or persists a private key.
 */
public interface CertificateProvider {

    /** Stable identifier for this provider, stored on the domain row. */
    String name();

    /**
     * Requests a certificate for {@code hostname} plus any {@code additionalNames}.
     *
     * @return the reference the platform stores, not the key material itself
     */
    IssuedCertificate issue(String hostname, List<String> additionalNames, CertificateRequest request);

    /** Current issuance state, empty when no request exists for the host. */
    Optional<CertificateStatus> status(String hostname);

    /** Cancels and removes the certificate; absent certificates are not an error. */
    void revoke(String hostname);

    /** What the tenant asked for. Null fields fall back to the adapter's configured defaults. */
    record CertificateRequest(String issuerKind, String issuerName, String secretName, String dnsZone) {
    }

    /**
     * The outcome of a request.
     *
     * @param secretName name of the Kubernetes {@code kubernetes.io/tls} Secret the
     *                   ingress consumes
     */
    record IssuedCertificate(String hostname, String secretName, String issuerName, Instant requestedAt) {
    }

    /**
     * Issuance state, as reported by the provider rather than assumed from the
     * request having been accepted.
     *
     * @param ready   true only once the certificate exists and is usable
     * @param notAfter expiry, null while still pending
     */
    record CertificateStatus(String hostname, boolean ready, Instant notAfter, String message) {

        /** True when the certificate will expire inside {@code window}, driving renewal alerts. */
        public boolean expiresWithin(Duration window) {
            return notAfter != null && notAfter.isBefore(Instant.now().plus(window));
        }
    }
}
