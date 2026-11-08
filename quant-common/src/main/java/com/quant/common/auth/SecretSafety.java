package com.quant.common.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Validates runtime secrets without exposing their values in errors or logs. */
public final class SecretSafety {
    private static final int MINIMUM_BYTES = 32;
    private static final List<String> UNSAFE_MARKERS = List.of(
            "change-me",
            "changeme",
            "replace-me",
            "replace_me",
            "your-secret",
            "your_secret",
            "placeholder",
            "default-secret",
            "default_secret",
            "example-secret",
            "example_secret");
    private static final Set<String> UNSAFE_VALUES = Set.of(
            "change-this-development-secret-to-at-least-32-bytes");

    private SecretSafety() {
    }

    /**
     * 验证配置值是否安全，返回字节数组
     * @param configurationName 配置项名称
     * @param value 配置值字符串
     * @return 配置值的字节数组表示
     */
    public static byte[] requireSecure(String configurationName, String value) {
        String trimmed = value == null ? "" : value.trim();// 移除首尾空格
        byte[] bytes = trimmed.getBytes(StandardCharsets.UTF_8);// 转换为字节数组
        String normalized = trimmed.toLowerCase(Locale.ROOT);// 转换为小写
        boolean unsafeMarker = UNSAFE_MARKERS.stream().anyMatch(normalized::contains);// 检查是否包含不安全的标记
        if (bytes.length < MINIMUM_BYTES || unsafeMarker || UNSAFE_VALUES.contains(normalized)) {// 检查是否包含不安全的值
            throw new IllegalStateException(configurationName + " must be securely configured");// 抛出异常
        }
        return bytes;
    }

    public static void requireDistinct(
            String firstName, byte[] first, String secondName, byte[] second) {
        if (MessageDigest.isEqual(first, second)) {
            throw new IllegalStateException(firstName + " and " + secondName + " must be different");
        }
    }
}
