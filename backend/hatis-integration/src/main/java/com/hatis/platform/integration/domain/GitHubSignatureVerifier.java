package com.hatis.platform.integration.domain;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Verifies GitHub's {@code X-Hub-Signature-256} header.
 *
 * <p>This is the only authentication an inbound webhook has, so it is deliberately
 * narrow and total: every malformed input is a rejection rather than an exception,
 * because a caller controls all of it.
 *
 * <p>Three properties matter and are easy to get wrong:
 * <ul>
 *   <li><strong>The raw body is signed.</strong> GitHub signs the exact bytes it sent.
 *       Re-serialising parsed JSON produces different bytes — key order, whitespace
 *       and escaping all differ — and every legitimate delivery would fail. Callers
 *       must therefore pass the untouched request body, not a bound DTO.</li>
 *   <li><strong>The comparison is constant time.</strong> A byte-at-a-time comparison
 *       leaks how much of the digest matched, which is enough to forge one byte at a
 *       time over many requests.</li>
 *   <li><strong>It fails closed.</strong> An absent, unprefixed, non-hex or empty
 *       secret is a rejection. A missing webhook secret must never degrade into
 *       "accept anything".</li>
 * </ul>
 */
public final class GitHubSignatureVerifier {

    /** Header carrying {@code sha256=<hex>}. */
    public static final String SIGNATURE_HEADER = "X-Hub-Signature-256";
    /** Header carrying GitHub's unique delivery GUID; the replay-protection key. */
    public static final String DELIVERY_HEADER = "X-GitHub-Delivery";
    /** Header naming the event type, e.g. {@code push}. */
    public static final String EVENT_HEADER = "X-GitHub-Event";

    private static final String MAC_ALGORITHM = "HmacSHA256";
    private static final String KEY_ALGORITHM = "HmacSHA256";
    private static final String PREFIX = "sha256=";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private GitHubSignatureVerifier() {
    }

    /**
     * True when {@code signatureHeader} is a valid HMAC-SHA256 of {@code rawBody}
     * under {@code secret}.
     *
     * @param signatureHeader the raw {@code X-Hub-Signature-256} value, may be null
     * @param rawBody         the exact request bytes; must not be null
     * @param secret          the shared webhook secret; null or empty rejects
     */
    public static boolean matches(String signatureHeader, byte[] rawBody, String secret) {
        if (rawBody == null || secret == null || secret.isEmpty() || signatureHeader == null) {
            return false;
        }
        if (!signatureHeader.startsWith(PREFIX)) {
            return false;
        }
        byte[] provided = decodeHex(signatureHeader.substring(PREFIX.length()));
        if (provided == null) {
            return false;
        }
        byte[] expected = sign(rawBody, secret.getBytes(StandardCharsets.UTF_8));
        if (expected == null) {
            return false;
        }
        // Constant time, and safe for differing lengths: the digest length is not
        // secret, so an early false there leaks nothing an attacker did not know.
        return MessageDigest.isEqual(expected, provided);
    }

    /**
     * Computes the header value GitHub would send, so the platform can display it in
     * tests and operators can reproduce a delivery.
     *
     * @throws IllegalArgumentException if the secret is absent, because signing with an
     *                                  empty key would produce a header that verifies
     *                                  against nothing
     */
    public static String signatureFor(byte[] rawBody, String secret) {
        if (rawBody == null || secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("a webhook secret is required to sign a payload");
        }
        byte[] digest = sign(rawBody, secret.getBytes(StandardCharsets.UTF_8));
        if (digest == null) {
            throw new IllegalStateException("HmacSHA256 is not available on this JVM");
        }
        StringBuilder out = new StringBuilder(PREFIX.length() + digest.length * 2).append(PREFIX);
        for (byte b : digest) {
            out.append(HEX[(b >> 4) & 0x0F]).append(HEX[b & 0x0F]);
        }
        return out.toString();
    }

    /** Returns null rather than throwing: a verifier must reject, not fail loudly. */
    private static byte[] sign(byte[] body, byte[] key) {
        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, KEY_ALGORITHM));
            return mac.doFinal(body);
        } catch (java.security.GeneralSecurityException e) {
            return null;
        }
    }

    /** Returns null on any non-hex or odd-length input. */
    private static byte[] decodeHex(String hex) {
        int length = hex.length();
        if (length == 0 || length % 2 != 0) {
            return null;
        }
        byte[] out = new byte[length / 2];
        for (int i = 0; i < length; i += 2) {
            int high = Character.digit(hex.charAt(i), 16);
            int low = Character.digit(hex.charAt(i + 1), 16);
            if (high < 0 || low < 0) {
                return null;
            }
            out[i / 2] = (byte) ((high << 4) | low);
        }
        return out;
    }
}
