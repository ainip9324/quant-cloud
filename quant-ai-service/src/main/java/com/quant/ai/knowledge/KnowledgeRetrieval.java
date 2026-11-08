package com.quant.ai.knowledge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** 可选的本机向量检索，只接受与当前公开资料对应的来源 ID。 */
@Component
class KnowledgeRetrieval {
    private static final int TOP_K = 4;
    private static final int MAX_RESPONSE_BYTES = 4096;

    private final RestClient client;
    private final ObjectMapper json;

    KnowledgeRetrieval(@Value("${ai.knowledge.retrieval-port:0}") int port, ObjectMapper json) {
        this.json = json;
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Knowledge retrieval port is out of range");
        }
        if (port == 0) {
            this.client = null;
            return;
        }
        var factory = new SimpleClientHttpRequestFactory();
        factory.setProxy(Proxy.NO_PROXY);
        factory.setConnectTimeout(500);
        factory.setReadTimeout(1000);
        this.client = RestClient.builder().requestFactory(factory)
                .baseUrl("http://127.0.0.1:" + port).build();
    }

    /** 按检索顺序从本地资料包选出处，连接失败或结果无效时使用整包。 */
    List<KnowledgeCatalog.Source> select(String question, String fingerprint, List<KnowledgeCatalog.Source> catalog) {
        if (client == null || catalog.size() <= TOP_K) {
            return catalog;
        }
        try {
            // Python 的回环接口按 Content-Length 读取有界请求体。
            byte[] payload = json.writeValueAsBytes(Map.of("question", question));
            return client.post().uri("/retrieve").contentType(MediaType.APPLICATION_JSON)
                    .contentLength(payload.length).body(payload)
                    .exchange((request, response) -> {
                        if (response.getStatusCode().value() != 200) {
                            return catalog;
                        }
                        byte[] body = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
                        if (body.length > MAX_RESPONSE_BYTES) {
                            return catalog;
                        }
                        var result = json.reader()
                                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                                        DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                                .readTree(body);
                        return selectSources(result, fingerprint, catalog);
                    });
        } catch (JsonProcessingException | RestClientException exception) {
            return catalog;
        }
    }

    /** 校验语料指纹、条数与来源身份，避免旧索引或未知 ID 进入提示词。 */
    private List<KnowledgeCatalog.Source> selectSources(
            JsonNode result, String fingerprint, List<KnowledgeCatalog.Source> catalog) {
        if (result == null || !fingerprint.equals(result.path("catalogSha256").textValue())) {
            return catalog;
        }
        var ids = result.path("sourceIds");
        if (!ids.isArray() || ids.size() != TOP_K) {
            return catalog;
        }
        var selected = new ArrayList<KnowledgeCatalog.Source>(TOP_K);
        var seen = new HashSet<String>();
        for (var id : ids) {
            if (!id.isTextual() || !seen.add(id.textValue())) {
                return catalog;
            }
            var source = catalog.stream().filter(item -> item.id().equals(id.textValue())).findFirst();
            if (source.isEmpty()) {
                return catalog;
            }
            selected.add(source.get());
        }
        return List.copyOf(selected);
    }
}
