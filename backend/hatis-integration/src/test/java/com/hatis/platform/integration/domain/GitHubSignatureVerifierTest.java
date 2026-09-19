package com.hatis.platform.integration.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GitHub webhook signature verification.
 *
 * <p>This is the only authentication an inbound delivery has, so the tests are written
 * around the ways it can be bypassed rather than around the happy path. Every expected
 * digest here was computed independently with a separate HMAC implementation, not
 * produced by the code under test — a test that asks the implementation for the answer
 * proves nothing.
 */
class GitHubSignatureVerifierTest {

    private static final String SECRET = "hatis-webhook-secret";
    private static final String BODY = "{\"ref\":\"refs/heads/main\",\"after\":\"abc123\"}";
    /** HMAC-SHA256 of {@link #BODY} under {@link #SECRET}, computed externally. */
    private static final String VALID =
            "sha256=593b0b6dd833b619e7f8ef7cdc46d865df52ca384824c12cce4ae7770021bce6";

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a correctly signed delivery is accepted")
    void acceptsAValidSignature() {
        assertThat(GitHubSignatureVerifier.matches(VALID, bytes(BODY), SECRET)).isTrue();
    }

    @Test
    @DisplayName("a signature from a different secret is rejected")
    void rejectsWrongSecret() {
        // HMAC-SHA256 of BODY under "another-secret", computed externally.
        String otherSecretSignature =
                "sha256=d3d5d16c97982cfe1e5faaa215606d1603c9e28b9dcf6583b8a0d61e40623fc6";
        assertThat(GitHubSignatureVerifier.matches(otherSecretSignature, bytes(BODY), SECRET))
                .isFalse();
    }

    @Test
    @DisplayName("a body altered after signing is rejected")
    void rejectsTamperedBody() {
        assertThat(GitHubSignatureVerifier.matches(VALID, bytes(BODY.replace("abc123", "def456")), SECRET))
                .isFalse();
    }

    @Test
    @DisplayName("an empty body still has to be signed correctly")
    void handlesEmptyBody() {
        // HMAC-SHA256 of the empty string under SECRET, computed externally.
        String emptyBodySignature =
                "sha256=107d2b97cb29d88d50702606461e9321633d5af917f597862f0c7f716a0b6080";
        assertThat(GitHubSignatureVerifier.matches(emptyBodySignature, new byte[0], SECRET)).isTrue();
        assertThat(GitHubSignatureVerifier.matches(VALID, new byte[0], SECRET)).isFalse();
    }

    @Test
    @DisplayName("a non-ASCII secret is encoded as UTF-8, matching GitHub")
    void handlesNonAsciiSecret() {
        String unicodeSecret = "secret-with-unicode-\u00e9";
        // HMAC-SHA256 of the body under that secret, computed externally.
        String signature =
                "sha256=bff3c5cc8571e6c2e89090853b36effa8257d89eabb8938a9bc049378aef1927";
        assertThat(GitHubSignatureVerifier.matches(signature,
                bytes("{\"zen\":\"Keep it logically awesome.\"}"), unicodeSecret)).isTrue();
    }

    @ParameterizedTest(name = "malformed header {0} is rejected")
    @ValueSource(strings = {
            "",
            "593b0b6dd833b619e7f8ef7cdc46d865df52ca384824c12cce4ae7770021bce6",
            "sha1=593b0b6dd833b619e7f8ef7cdc46d865df52ca384824c12cce4ae7770021bce6",
            "sha256=",
            "sha256=not-hex-at-all",
            "sha256=593b0b6dd833b619e7f8ef7cdc46d865df52ca384824c12cce4ae7770021bce",
            "sha256=593b0b6dd833b619e7f8ef7cdc46d865df52ca384824c12cce4ae7770021bce6ff",
            "SHA256=593B0B6DD833B619E7F8EF7CDC46D865DF52CA384824C12CCE4AE7770021BCE6"
    })
    @DisplayName("a malformed, truncated, extended or mis-prefixed header is rejected")
    void rejectsMalformedHeaders(String header) {
        assertThat(GitHubSignatureVerifier.matches(header, bytes(BODY), SECRET)).isFalse();
    }

    @Test
    @DisplayName("an absent header is rejected rather than throwing")
    void rejectsAbsentHeader() {
        assertThat(GitHubSignatureVerifier.matches(null, bytes(BODY), SECRET)).isFalse();
    }

    @Test
    @DisplayName("a missing or empty secret fails closed instead of accepting everything")
    void failsClosedWithoutASecret() {
        // The dangerous regression: an unconfigured webhook secret must never mean
        // "no verification". Both of these must stay false.
        assertThat(GitHubSignatureVerifier.matches(VALID, bytes(BODY), null)).isFalse();
        assertThat(GitHubSignatureVerifier.matches(VALID, bytes(BODY), "")).isFalse();
    }

    @Test
    @DisplayName("a null body is rejected rather than throwing")
    void rejectsNullBody() {
        assertThat(GitHubSignatureVerifier.matches(VALID, null, SECRET)).isFalse();
    }

    @Test
    @DisplayName("signatureFor round-trips through matches")
    void signatureForRoundTrips() {
        String signature = GitHubSignatureVerifier.signatureFor(bytes(BODY), SECRET);
        assertThat(signature).isEqualTo(VALID);
        assertThat(GitHubSignatureVerifier.matches(signature, bytes(BODY), SECRET)).isTrue();
    }

    @Test
    @DisplayName("signatureFor refuses to sign without a secret")
    void signatureForRequiresASecret() {
        assertThatThrownBy(() -> GitHubSignatureVerifier.signatureFor(bytes(BODY), ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GitHubSignatureVerifier.signatureFor(bytes(BODY), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("uppercase hex is accepted, because it is the same digest")
    void acceptsUppercaseHex() {
        // GitHub sends lowercase. Accepting uppercase does not weaken the check - the
        // digest still has to match - and rejecting it would only cause spurious 401s
        // for a proxy that normalises headers.
        String upper = "sha256=" + VALID.substring("sha256=".length()).toUpperCase();
        assertThat(GitHubSignatureVerifier.matches(upper, bytes(BODY), SECRET)).isTrue();
    }
}
