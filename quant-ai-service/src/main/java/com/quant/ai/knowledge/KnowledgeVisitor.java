package com.quant.ai.knowledge;

import com.quant.common.auth.SecretSafety;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 通过签名 Cookie 识别同一浏览器的访客额度。 */
@Component
class KnowledgeVisitor {
    static final String COOKIE = "qc_knowledge_visitor";
    // Cookie 由 16 字节随机 ID 和 HMAC-SHA256 签名组成，两段均使用无填充的 Base64URL。
    private static final Pattern SIGNED_ID = Pattern.compile("[A-Za-z0-9_-]{22}\\.[A-Za-z0-9_-]{43}");
    private static final Base64.Encoder BASE64 = Base64.getUrlEncoder().withoutPadding();
    private final SecureRandom random = new SecureRandom();
    private final byte[] signingKey;

    /** 从已校验的 JWT 密钥派生访客 Cookie 专用的签名密钥。 */
    KnowledgeVisitor(@Value("${auth.jwt-secret}") String secret) {
        // 从现有 JWT 密钥派生问答专用的签名密钥，隔离两种签名用途。
        signingKey = hmac(SecretSafety.requireSecure("JWT_SECRET", secret), "quantcloud:knowledge-visitor:v1");
    }

    /** 校验现有访客 Cookie，必要时分配新访客 ID 并返回待设置的 Cookie。 */
    Identity resolve(String cookie) {
        if (cookie != null && SIGNED_ID.matcher(cookie).matches()) {
            String id = cookie.substring(0, 22);
            if (MessageDigest.isEqual(
                    signed(id).getBytes(StandardCharsets.US_ASCII),
                    cookie.getBytes(StandardCharsets.US_ASCII))) {
                return new Identity(id, null);
            }
        }

        // Cookie 缺失或校验失败时分配新 ID，所有访客仍共用同一个访客池额度。
        byte[] entropy = new byte[16];
        random.nextBytes(entropy);
        String id = BASE64.encodeToString(entropy);
        return new Identity(id, signed(id));
    }

    /** 将访客 ID 与其签名拼成 Cookie 值。 */
    private String signed(String id) {
        return id + "." + BASE64.encodeToString(hmac(signingKey, id));
    }

    /** 计算指定数据的 HMAC-SHA256 签名。 */
    private static byte[] hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Cannot sign knowledge visitor", exception);
        }
    }

    record Identity(String id, String newCookie) {
    }
}
