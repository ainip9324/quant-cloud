package com.quant.common.auth;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class JwtV2Verifier {
    public static final int MAX_TOKEN_LENGTH = 4096;

    private final byte[] secret;
    private final Clock clock;

    public JwtV2Verifier(byte[] secret, Clock clock) {
        this.secret = requireSecret(secret);
        this.clock = clock;
    }

    public AuthenticatedPrincipal verify(String token) {
        try {
            if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
                throw invalid();
            }
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3) {
                throw invalid();
            }
            for (String part : parts) {
                requireBase64Url(part);
            }
            byte[] expected = decode(sign(parts[0] + "." + parts[1], secret));
            byte[] supplied = decode(parts[2]);
            if (!MessageDigest.isEqual(expected, supplied)) {
                throw invalid();
            }
            JSONObject header = parse(parts[0]);
            if (!"HS256".equals(header.getString("alg"))) {
                throw invalid();
            }
            JSONObject claims = parse(parts[1]);
            Object sub = claims.get("sub");
            Object version = claims.get("ver");
            Object expiration = claims.get("exp");
            Object role = claims.get("role");
            if (!(sub instanceof Number) || !(version instanceof Number)
                    || !(expiration instanceof Number) || !(role instanceof String)) {
                throw invalid();
            }
            long userId = exactLong((Number) sub);
            long ver = exactLong((Number) version);
            long exp = exactLong((Number) expiration);
            if (userId <= 0 || ver != 2 || exp <= clock.instant().getEpochSecond()) {
                throw invalid();
            }
            return new AuthenticatedPrincipal(userId, UserRole.valueOf((String) role));
        } catch (IllegalArgumentException exception) {
            throw invalid();
        } catch (Exception exception) {
            throw invalid();
        }
    }

    static byte[] requireSecret(byte[] value) {
        if (value == null || value.length < 32) {
            throw new IllegalArgumentException("JWT secret must contain at least 32 bytes");
        }
        return Arrays.copyOf(value, value.length);
    }

    static String sign(String value, byte[] secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to sign token", exception);
        }
    }

    private static JSONObject parse(String part) {
        Object value = JSON.parse(new String(decode(part), StandardCharsets.UTF_8));
        if (!(value instanceof JSONObject object)) {
            throw invalid();
        }
        return object;
    }

    private static byte[] decode(String part) {
        return Base64.getUrlDecoder().decode(part);
    }

    private static void requireBase64Url(String part) {
        if (part.isEmpty()) {
            throw invalid();
        }
        for (int index = 0; index < part.length(); index++) {
            char c = part.charAt(index);
            boolean valid = c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z'
                    || c >= '0' && c <= '9' || c == '-' || c == '_';
            if (!valid) {
                throw invalid();
            }
        }
        String canonical = Base64.getUrlEncoder().withoutPadding().encodeToString(decode(part));
        if (!canonical.equals(part)) {
            throw invalid();
        }
    }

    private static long exactLong(Number number) {
        if (number instanceof Byte || number instanceof Short
                || number instanceof Integer || number instanceof Long) {
            return number.longValue();
        }
        if (number instanceof BigInteger bigInteger) {
            try {
                return bigInteger.longValueExact();
            } catch (ArithmeticException exception) {
                throw invalid();
            }
        }
        throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid token");
    }
}
