package com.hatis.platform.integration.domain;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * Signs outbound webhook deliveries so a receiving system can prove the request came
 * from this platform and was not altered in transit.
 *
 * <p>The timestamp is part of the signed material rather than a separate unsigned
 * header. Signing only the body would let an attacker capture one valid delivery and
 * replay it indefinitely; binding the timestamp means a receiver that rejects anything
 * older than a few minutes also rejects the replay. This is the same shape Stripe uses,
 * chosen because receiving systems already have libraries that parse it.
 *
 * <p>Header format: {@code t=<unix seconds>,v1=<hex hmac>}.
 */
public final class WebhookSigner {

    /** Header carrying the signature. */
    public static final String SIGNATURE_HEADER = "X-Hatis-Signature";
    /** Header naming the event type, so a receiver can route before parsing. */
    public static final String EVENT_HEADER = "X-Hatis-Event";
    /** Header carrying the delivery identifier, for the receiver's own idempotency. */
    public static final String DELIVERY_HEADER = "X-Hatis-Delivery";

    private static final String MAC_ALGORITHM = "HmacSHA256";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private WebhookSigner() {
    }

    /**
     * Produces the {@link #SIGNATURE_HEADER} value for a delivery.
     *
     * @param payload   the exact bytes that will be sent; the receiver must sign the raw
     *                  body it received, not a re-serialised parse of it
     * @param secret    the endpoint's shared secret
     * @param timestamp when the delivery was created, bound into the signature
     * @throws IllegalArgumentException if the secret is absent, because an unsigned
     *                                  delivery would still look legitimate to a
     *                                  receiver that forgot to check
     */
    public static String sign(String payload, String secret, Instant timestamp) {
        if (secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("a webhook secret is required to sign a delivery");
        }
        if (payload == null || timestamp == null) {
            throw new IllegalArgumentException("payload and timestamp are required");
        }
        long epochSecond = timestamp.getEpochSecond();
        String signed = epochSecond + "." + payload;
        byte[] digest = hmac(signed.getBytes(StandardCharsets.UTF_8),
                secret.getBytes(StandardCharsets.UTF_8));
        return "t=" + epochSecond + ",v1=" + toHex(digest);
    }

    /**
     * Verifies a signature in the platform's own format.
     *
     * <p>Receivers are third parties and cannot use this, but the platform needs it to
     * test that what it publishes is what it claims to publish, and it documents the
     * exact algorithm a receiver must implement.
     *
     * @param signatureHeader the full header value, {@code t=...,v1=...}
     * @return true only when the timestamp and the digest both match
     */
    public static boolean verify(String signatureHeader, String payload, String secret, Instant timestamp) {
        if (signatureHeader == null || secret == null || secret.isEmpty() || payload == null
                || timestamp == null) {
            return false;
        }
        String expected = sign(payload, secret, timestamp);
        // Constant time: the digest is the secret-bearing part of the comparison.
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                signatureHeader.getBytes(StandardCharsets.UTF_8));
    }

    /** Extracts the timestamp component, or -1 when the header is malformed. */
    public static long timestampOf(String signatureHeader) {
        if (signatureHeader == null) {
            return -1;
        }
        for (String part : signatureHeader.split(",")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("t=")) {
                try {
                    return Long.parseLong(trimmed.substring(2));
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }

    private static byte[] hmac(byte[] data, byte[] key) {
        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, MAC_ALGORITHM));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable on this JVM", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(HEX[(b >> 4) & 0x0F]).append(HEX[b & 0x0F]);
        }
        return out.toString();
    }
}
