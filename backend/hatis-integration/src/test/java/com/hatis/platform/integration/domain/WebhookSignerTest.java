package com.hatis.platform.integration.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Outbound delivery signing.
 *
 * <p>Expected digests were computed with a separate HMAC implementation. The property
 * that matters is that the timestamp is inside the signed material, so a captured
 * delivery cannot be replayed once the receiver starts rejecting old timestamps.
 */
class WebhookSignerTest {

    private static final String SECRET = "hwhsec_outbound_test_secret";
    private static final String PAYLOAD = "{\"type\":\"content.published\",\"id\":\"abc\"}";
    private static final Instant WHEN = Instant.ofEpochSecond(1_800_000_000L);

    @Test
    @DisplayName("a signature matches the externally computed value")
    void producesTheExpectedSignature() {
        assertThat(WebhookSigner.sign(PAYLOAD, SECRET, WHEN))
                .isEqualTo("t=1800000000,v1=a32e6e827eb591300cfce33c6f7543a2da5a19d1abb1c73ce7ce688451628b86");
    }

    @Test
    @DisplayName("an empty payload is still signed")
    void signsEmptyPayload() {
        assertThat(WebhookSigner.sign("", SECRET, WHEN))
                .isEqualTo("t=1800000000,v1=7c2a3c0d10ad07f22f0ac82c94878993c8b40e6d079b5e7e623866317e372a50");
    }

    @Test
    @DisplayName("a non-ASCII secret is encoded as UTF-8")
    void handlesNonAsciiSecret() {
        assertThat(WebhookSigner.sign("{\"zen\":\"Keep it logically awesome.\"}",
                "secret-\u00e9-unicode", Instant.ofEpochSecond(1_700_000_000L)))
                .isEqualTo("t=1700000000,v1=026e7f726b6cc7db1e5d282b2828afa67dfe71990ab3726c291103b89405fba9");
    }

    @Test
    @DisplayName("the timestamp is bound into the signature, so a replayed body fails")
    void timestampIsPartOfTheSignedMaterial() {
        String signedAtOne = WebhookSigner.sign(PAYLOAD, SECRET, WHEN);
        String signedAtTwo = WebhookSigner.sign(PAYLOAD, SECRET, WHEN.plusSeconds(1));

        assertThat(signedAtTwo).isNotEqualTo(signedAtOne);
        assertThat(signedAtTwo)
                .isEqualTo("t=1800000001,v1=d70b953383e156953a9f699bfb0138ce4da1db6ffdde3cf1b3a7b9d27c387dba");

        // The crux: the same body under a different timestamp does not verify. An
        // attacker replaying a captured delivery has the old timestamp, and a receiver
        // that rejects stale timestamps rejects the replay with it.
        assertThat(WebhookSigner.verify(stripTimestamp(signedAtOne, 1_800_000_001L),
                PAYLOAD, SECRET, WHEN.plusSeconds(1))).isFalse();
    }

    @Test
    @DisplayName("a signature from a different secret does not verify")
    void rejectsWrongSecret() {
        assertThat(WebhookSigner.sign(PAYLOAD, "hwhsec_other_secret", WHEN))
                .isEqualTo("t=1800000000,v1=8aabf9e6cf83ebbebf9977c2c3d1f75fa1be72dee5d0fdc8ec9e4964160197c9");
        assertThat(WebhookSigner.verify(
                WebhookSigner.sign(PAYLOAD, "hwhsec_other_secret", WHEN), PAYLOAD, SECRET, WHEN))
                .isFalse();
    }

    @Test
    @DisplayName("an altered payload does not verify")
    void rejectsAlteredPayload() {
        String signature = WebhookSigner.sign(PAYLOAD, SECRET, WHEN);
        assertThat(WebhookSigner.verify(signature, PAYLOAD.replace("abc", "xyz"), SECRET, WHEN)).isFalse();
    }

    @Test
    @DisplayName("a signature round-trips through verify")
    void roundTrips() {
        String signature = WebhookSigner.sign(PAYLOAD, SECRET, WHEN);
        assertThat(WebhookSigner.verify(signature, PAYLOAD, SECRET, WHEN)).isTrue();
    }

    @Test
    @DisplayName("signing refuses to proceed without a secret")
    void requiresASecret() {
        // An unsigned delivery would still look well-formed to a receiver that forgot to
        // check, so refusing here is safer than emitting something meaningless.
        assertThatThrownBy(() -> WebhookSigner.sign(PAYLOAD, "", WHEN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookSigner.sign(PAYLOAD, null, WHEN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("verify rejects malformed and absent input instead of throwing")
    void verifyIsTotal() {
        assertThat(WebhookSigner.verify(null, PAYLOAD, SECRET, WHEN)).isFalse();
        assertThat(WebhookSigner.verify("garbage", PAYLOAD, SECRET, WHEN)).isFalse();
        assertThat(WebhookSigner.verify("t=1800000000,v1=", PAYLOAD, SECRET, WHEN)).isFalse();
        assertThat(WebhookSigner.verify(WebhookSigner.sign(PAYLOAD, SECRET, WHEN), PAYLOAD, "", WHEN))
                .isFalse();
        assertThat(WebhookSigner.verify(WebhookSigner.sign(PAYLOAD, SECRET, WHEN), PAYLOAD, SECRET, null))
                .isFalse();
    }

    @Test
    @DisplayName("the timestamp can be read back so a receiver can reject stale deliveries")
    void readsTheTimestamp() {
        assertThat(WebhookSigner.timestampOf(WebhookSigner.sign(PAYLOAD, SECRET, WHEN)))
                .isEqualTo(1_800_000_000L);
        assertThat(WebhookSigner.timestampOf("garbage")).isEqualTo(-1);
        assertThat(WebhookSigner.timestampOf("t=notanumber,v1=abc")).isEqualTo(-1);
        assertThat(WebhookSigner.timestampOf(null)).isEqualTo(-1);
    }

    /** Rebuilds a header with the same digest but a different timestamp, as a replay would. */
    private static String stripTimestamp(String header, long newTimestamp) {
        return "t=" + newTimestamp + ",v1=" + header.substring(header.indexOf("v1=") + 3);
    }
}
