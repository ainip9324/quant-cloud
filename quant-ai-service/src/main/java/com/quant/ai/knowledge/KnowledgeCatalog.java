package com.quant.ai.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** 加载发布时随应用打包的公开资料，供提示词和响应引用共用。 */
@Component
class KnowledgeCatalog {
    private final Snapshot snapshot;

    KnowledgeCatalog(ObjectMapper json) {
        snapshot = load(json);
    }

    /** 提供本次问答可用的公开资料包，空资料包返回问答不可用。 */
    List<Source> sources() {
        if (snapshot.sources().isEmpty()) {
            throw KnowledgeException.unavailable();
        }
        return snapshot.sources();
    }

    /** 标识与检索进程共用的公开资料版本。 */
    String fingerprint() {
        return snapshot.fingerprint();
    }

    /** 读取并校验固定公开语料，检查资料 ID 和序列化长度。 */
    private static Snapshot load(ObjectMapper json) {
        try (var input = new ClassPathResource("knowledge/public-sources.json").getInputStream()) {
            byte[] bytes = input.readAllBytes();
            List<Source> loaded = json.readValue(bytes, new TypeReference<>() { });
            var ids = new HashSet<String>();
            // 每次发送整包资料，因此限制的是序列化后的总长度，包含标题、版本和链接。
            if (loaded.stream().anyMatch(source -> !ids.add(source.id()))
                    || json.writeValueAsString(loaded).length() > 2400) {
                throw new IllegalArgumentException("Invalid public knowledge catalog");
            }
            // 允许 Git 在 Windows 检出 CRLF，资料内容的其他变化都应得到新的指纹。
            byte[] normalized = new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n")
                    .getBytes(StandardCharsets.UTF_8);
            var digest = MessageDigest.getInstance("SHA-256").digest(normalized);
            return new Snapshot(List.copyOf(loaded), HexFormat.of().formatHex(digest));
        } catch (IOException | NoSuchAlgorithmException | RuntimeException exception) {
            // 整包校验失败时，问答返回不可用，应用其余功能仍能启动。
            LoggerFactory.getLogger(KnowledgeCatalog.class).warn("Public knowledge catalog unavailable");
            return new Snapshot(List.of(), "");
        }
    }

    private record Snapshot(List<Source> sources, String fingerprint) {}

    record Source(
            String id,
            String title,
            String category,
            String version,
            String content,
            String url) {
        Source {
            if (id == null || id.isBlank()
                    || title == null || title.isBlank()
                    || category == null || category.isBlank()
                    || version == null || version.isBlank()
                    || content == null || content.isBlank()
                    || url == null || !url.startsWith("https://")) {
                throw new IllegalArgumentException("Incomplete public knowledge source");
            }
        }
    }
}
