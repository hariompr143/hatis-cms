package com.hatis.platform.identity.application;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

/**
 * TOTP (RFC 6238) generation and verification.
 *
 * <p>Implemented directly rather than pulled from a library: the algorithm is
 * small, and owning it means the verification window, the replay guard and the
 * constant-time comparison are all visible in one place and covered by tests.
 *
 * <p>Parameters: SHA-1, 6 digits, 30-second step — the settings every
 * authenticator app supports by default.
 */
@Component
public class TotpService {

    public static final int DIGITS = 6;
    public static final int STEP_SECONDS = 30;
    /** ±1 step of clock skew, which is what real authenticator apps tolerate. */
    public static final int WINDOW = 1;

    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Generates a 160-bit secret and returns it in base32, as authenticators expect. */
    public String generateSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return base32Encode(bytes);
    }

    public String otpauthUri(String issuer, String account, String base32Secret) {
        return "otpauth://totp/" + urlEncode(issuer) + ":" + urlEncode(account)
                + "?secret=" + base32Secret
                + "&issuer=" + urlEncode(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    public String currentCode(String base32Secret) {
        return codeAt(base32Secret, timeStep(System.currentTimeMillis() / 1000L));
    }

    /**
     * Verifies a code within the skew window.
     *
     * @return the matched time step, or -1 when no code in the window matched
     */
    public long verify(String base32Secret, String code) {
        if (code == null) {
            return -1;
        }
        String candidate = code.trim();
        if (candidate.length() != DIGITS || !candidate.chars().allMatch(Character::isDigit)) {
            return -1;
        }
        long step = timeStep(System.currentTimeMillis() / 1000L);
        for (int offset = -WINDOW; offset <= WINDOW; offset++) {
            String expected = codeAt(base32Secret, step + offset);
            if (MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    candidate.getBytes(StandardCharsets.UTF_8))) {
                return step + offset;
            }
        }
        return -1;
    }

    String codeAt(String base32Secret, long step) {
        byte[] key = base32Decode(base32Secret);
        byte[] counter = ByteBuffer.allocate(8).putLong(step).array();
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(counter);
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            int otp = binary % (int) Math.pow(10, DIGITS);
            return String.format(Locale.ROOT, "%0" + DIGITS + "d", otp);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 is unavailable", e);
        }
    }

    /** Generates single-use recovery codes. Each is hashed individually before storage. */
    public java.util.List<String> generateRecoveryCodes(int count) {
        java.util.List<String> codes = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            byte[] bytes = new byte[5];
            RANDOM.nextBytes(bytes);
            String hex = java.util.HexFormat.of().formatHex(bytes);
            codes.add(hex.substring(0, 5) + "-" + hex.substring(5));
        }
        return java.util.List.copyOf(codes);
    }

    static long timeStep(long epochSeconds) {
        return epochSeconds / STEP_SECONDS;
    }

    static String base32Encode(byte[] data) {
        StringBuilder out = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                out.append(BASE32_ALPHABET.charAt((buffer >> bits) & 0x1F));
            }
        }
        if (bits > 0) {
            out.append(BASE32_ALPHABET.charAt((buffer << (5 - bits)) & 0x1F));
        }
        return out.toString();
    }

    static byte[] base32Decode(String value) {
        String clean = value.trim().toUpperCase(Locale.ROOT).replace("=", "");
        int buffer = 0;
        int bits = 0;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (char c : clean.toCharArray()) {
            int index = BASE32_ALPHABET.indexOf(c);
            if (index < 0) {
                throw new IllegalArgumentException("Invalid base32 character");
            }
            buffer = (buffer << 5) | index;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                out.write((buffer >> bits) & 0xFF);
            }
        }
        return out.toByteArray();
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Exposed for tests and for operators who need a deterministic vector. */
    static String base64Of(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }
}
