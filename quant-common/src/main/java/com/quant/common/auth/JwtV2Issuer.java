package com.quant.common.auth;

import com.alibaba.fastjson2.JSON;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class JwtV2Issuer {
    private final byte[] secret;
    private final Clock clock;

    public JwtV2Issuer(byte[] secret, Clock clock) {
        this.secret = JwtV2Verifier.requireSecret(secret);
        this.clock = clock;
    }

    public String issue(AuthenticatedPrincipal principal, long lifetimeSeconds) {
        if (lifetimeSeconds <= 0) {
            throw new IllegalArgumentException("Token lifetime must be positive");
        }
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "HS256");
        header.put("typ", "JWT");
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", principal.userId());
        claims.put("role", principal.role().name());
        claims.put("ver", 2);
        claims.put("exp", clock.instant().getEpochSecond() + lifetimeSeconds);
        String unsigned = encode(JSON.toJSONString(header)) + "." + encode(JSON.toJSONString(claims));
        return unsigned + "." + JwtV2Verifier.sign(unsigned, secret);
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
