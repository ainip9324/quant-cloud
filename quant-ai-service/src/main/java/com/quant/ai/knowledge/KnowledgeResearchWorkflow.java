package com.quant.ai.knowledge;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.openai.api.OpenAiApi.FunctionTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/** 组合一次公开报价和已经生成的知识解释，行情不可用时保留解释。 */
@Component
class KnowledgeResearchWorkflow {
    private static final String TOOL = "getPublicQuoteWithExplanation";
    private static final int MAX_EXPLANATION_CHARS = 1000;

    private final ObjectMapper json;
    private final CompiledGraph graph;

    KnowledgeResearchWorkflow(KnowledgeQuoteTool quote, ObjectMapper json,
            @Value("${ai.knowledge.research-workflow-enabled:false}") boolean enabled) {
        this.json = json;
        graph = enabled && quote.enabled() ? compile(quote) : null;
    }

    boolean enabled() {
        return graph != null;
    }

    /** 给模型描述混合请求所需的报价参数与已经写好的概念解释。 */
    static FunctionTool definition() {
        return new FunctionTool(new FunctionTool.Function(
                "组合一个受支持标的的当前 USDT 永续报价和一个独立的交易概念解释。",
                TOOL,
                Map.of("type", "object", "additionalProperties", false,
                        "required", List.of("symbol", "explanation", "sourceIds"),
                        "properties", Map.of("symbol", KnowledgeQuoteTool.symbolSchema(),
                                "explanation", Map.of("type", "string", "maxLength", MAX_EXPLANATION_CHARS),
                                "sourceIds", Map.of("type", "array", "items", Map.of("type", "string")))),
                null));
    }

    /** 判断模型是否选择了开启的混合问答工具。 */
    boolean accepts(ToolCall call) {
        return enabled() && TOOL.equals(call.name());
    }

    /** 先检查参数结构，调用方随后对照本次上下文核验出处。 */
    ResearchRequest parse(ToolCall call) throws JsonProcessingException {
        if (!accepts(call) || !"function".equals(call.type())
                || call.arguments() == null || call.arguments().length() > 8192) {
            throw KnowledgeException.modelFailed();
        }
        var args = json.reader()
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .readTree(call.arguments());
        if (args == null || !args.isObject() || args.size() != 3 || !args.path("symbol").isTextual()
                || !args.path("explanation").isTextual() || !args.path("sourceIds").isArray()) {
            throw KnowledgeException.modelFailed();
        }
        String explanation = args.get("explanation").textValue().strip();
        if (explanation.isBlank() || explanation.length() > MAX_EXPLANATION_CHARS) {
            throw KnowledgeException.modelFailed();
        }
        return new ResearchRequest(args.get("symbol").textValue(), explanation, args.get("sourceIds"));
    }

    /** 执行已编译的图，以当前请求的状态合成一条混合回答。 */
    String answer(ResearchRequest request, boolean grounded) {
        String heading = grounded ? "知识解释" : "通用知识 · 模型讲解";
        return graph.invoke(Map.of("symbol", request.symbol(), "explanation", heading + "\n" + request.explanation()))
                .map(state -> text(state, "answer"))
                .orElseThrow(KnowledgeException::modelFailed);
    }

    /** 报价有效时先展示行情，行情不可用时先保留用户仍可使用的知识解释。 */
    private static CompiledGraph compile(KnowledgeQuoteTool quote) {
        try {
            return new StateGraph("knowledge-research", () -> Map.of(
                    "symbol", new ReplaceStrategy(), "explanation", new ReplaceStrategy(),
                    "quote", new ReplaceStrategy(), "live", new ReplaceStrategy(), "answer", new ReplaceStrategy()))
                    .addNode("readQuote", node_async(state -> {
                        var result = quote.quote(text(state, "symbol"));
                        return Map.of("quote", result.text(), "live", "LIVE_QUOTE".equals(result.status()));
                    }))
                    .addNode("quoteFirst", node_async(state ->
                            Map.of("answer", text(state, "quote") + "\n\n" + text(state, "explanation"))))
                    .addNode("explanationFirst", node_async(state ->
                            Map.of("answer", text(state, "explanation") + "\n\n" + text(state, "quote"))))
                    .addEdge(StateGraph.START, "readQuote")
                    .addConditionalEdges("readQuote", edge_async(state ->
                                    state.value("live", false) ? "live" : "unavailable"),
                            Map.of("live", "quoteFirst", "unavailable", "explanationFirst"))
                    .addEdge("quoteFirst", StateGraph.END)
                    .addEdge("explanationFirst", StateGraph.END)
                    .compile();
        } catch (GraphStateException exception) {
            throw new IllegalStateException("Cannot compile the knowledge research workflow", exception);
        }
    }

    private static String text(OverAllState state, String key) {
        return state.value(key, String.class).orElseThrow(KnowledgeException::modelFailed);
    }

    record ResearchRequest(String symbol, String explanation, JsonNode sourceIds) {}
}
