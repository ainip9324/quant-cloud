package com.quant.ai.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.quant.ai.error.AiErrorCode;
import com.quant.ai.error.AiModelException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 模型输出的第一道校验：限制响应字节数，再解析JSON并验证固定Schema。 */
@Component
public class ReportSchemaValidator {
    private final ObjectMapper mapper;
    private final JsonSchema schema;
    private final AiReportProperties properties;

    /** 加载固定的复盘 JSON Schema 供后续校验复用。 */
    public ReportSchemaValidator(ObjectMapper mapper, PromptBuilder prompt, AiReportProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
        this.schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(prompt.schema());
    }

    /** 校验模型原文的字节数、JSON 语法和复盘输出结构。 */
    public JsonNode validate(String content) {
        // 这里限制模型原文；组装成完整报告后的入库大小由AiReportService另行检查。
        if (content == null || content.isBlank() || content.getBytes(StandardCharsets.UTF_8).length > properties.responseByteLimit())
            throw invalid("output empty or too large");

        try {
            JsonNode node = mapper.readTree(content);
            Set<?> errors = schema.validate(node);
            if (!errors.isEmpty())
                throw invalid("schema validation failed");
            return node;
        } catch (AiModelException ex) {
            throw ex;
        } catch (Exception ex) {
            throw invalid("invalid JSON output");
        }
    }

    /** 生成统一的输出格式错误，供服务层判断是否允许修复。 */
    private AiModelException invalid(String safe) {
        // 统一OUTPUT_INVALID供Service判断是否尝试修复；不暴露原始解析异常。
        return new AiModelException(AiErrorCode.AI_REPORT_OUTPUT_INVALID, safe);
    }
}
