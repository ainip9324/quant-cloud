package com.quant.ai.knowledge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.ai.config.AiProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 编排单次问答，从并发准入和额度预扣到模型输出与引用校验。 */
@Service
class KnowledgeService {
    private static final int MAX_QUESTION_CHARS = 300;
    private static final int MAX_ANSWER_CHARS = 2400;
    private static final Duration MAX_WAIT = Duration.ofSeconds(30);

    private final KnowledgeProperties properties;
    private final AiProperties ai;
    private final KnowledgeCatalog catalog;
    private final KnowledgeRetrieval retrieval;
    private final KnowledgeQuoteTool quote;
    private final KnowledgeResearchWorkflow research;
    private final KnowledgeQuota quota;
    private final KnowledgeModel model;
    private final ObjectMapper json;
    private final ThreadPoolExecutor executor;
    // 问答和复盘共用线程池，单实例最多接受两个尚未结束的问答任务。
    private final Semaphore slots = new Semaphore(2);

    KnowledgeService(
            KnowledgeProperties properties,
            AiProperties ai,
            KnowledgeCatalog catalog,
            KnowledgeRetrieval retrieval,
            KnowledgeQuoteTool quote,
            KnowledgeResearchWorkflow research,
            KnowledgeQuota quota,
            KnowledgeModel model,
            ObjectMapper json,
            ThreadPoolExecutor aiModelCallExecutor) {
        this.properties = properties;
        this.ai = ai;
        this.catalog = catalog;
        this.retrieval = retrieval;
        this.quote = quote;
        this.research = research;
        this.quota = quota;
        this.model = model;
        this.json = json;
        this.executor = aiModelCallExecutor;
    }

    /** 从请求 JSON 取出问题，校验字段、字符数与控制字符。 */
    static String questionFrom(JsonNode body) {
        if (body == null || !body.isObject() || body.size() != 1 || !body.path("question").isTextual()) {
            throw KnowledgeException.invalid();
        }
        String question = body.get("question").textValue().strip();
        // 按 Unicode 码点计数，与浮窗的字符限制一致。多行文本允许保留换行和制表符。
        if (question.isBlank()
                || question.codePointCount(0, question.length()) > MAX_QUESTION_CHARS
                || question.codePoints().anyMatch(value -> Character.isISOControl(value)
                        && value != '\n' && value != '\r' && value != '\t')) {
            throw KnowledgeException.invalid();
        }
        return question;
    }

    /** 为单轮问答申请并发名额，并在有效期限内等待生成结果。 */
    Reply ask(String question, String actor, boolean visitor) {
        Duration wait = ai.getModelTimeout().compareTo(MAX_WAIT) < 0 ? ai.getModelTimeout() : MAX_WAIT;
        if (!properties.enabled() || !model.ready() || wait.isNegative() || wait.isZero()) {
            throw KnowledgeException.unavailable();
        }
        var sources = catalog.sources();
        if (!slots.tryAcquire()) {
            throw KnowledgeException.unavailable();
        }

        var job = new FutureTask<>(() -> generate(question, actor, visitor, sources)) {
            @Override
            public void run() {
                try {
                    super.run();
                } finally {
                    // 即使 HTTP 请求已超时，仍要等工作线程退出后才归还名额。
                    slots.release();
                }
            }
        };
        try {
            executor.execute(job);
        } catch (RejectedExecutionException exception) {
            slots.release();
            throw KnowledgeException.unavailable();
        }
        try {
            return job.get(wait.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            cancel(job);
            throw new KnowledgeException(HttpStatus.GATEWAY_TIMEOUT, "KNOWLEDGE_TIMEOUT", "回答超时，请稍后再试。");
        } catch (InterruptedException exception) {
            cancel(job);
            // 捕获 InterruptedException 会清除中断标记，这里恢复它以便调用链感知。
            Thread.currentThread().interrupt();
            throw KnowledgeException.unavailable();
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof KnowledgeException failure) {
                throw failure;
            }
            throw KnowledgeException.modelFailed();
        }
    }

    /** 取消问答任务，若任务仍在队列中则立即归还并发名额。 */
    private void cancel(FutureTask<?> job) {
        job.cancel(true);
        // 从队列移除后，任务不会进入 run()，因此由取消方归还名额。
        if (executor.remove(job)) {
            slots.release();
        }
    }

    /** 扣额后选择公开资料，发送原始问题并校验本次回答与出处。 */
    private Reply generate(String question, String actor, boolean visitor, List<KnowledgeCatalog.Source> publicSources)
            throws JsonProcessingException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
        // 模型调用前预扣额度，预扣成功后不退款。预扣期间若收到取消，跳过模型调用。
        var reservation = quota.reserve(actor, visitor);
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
        // 模拟模型返回固定引用，所以模拟模式始终使用整包。
        var sources = ai.getModelProvider() == AiProperties.ModelProvider.FAKE ? publicSources
                : retrieval.select(question, catalog.fingerprint(), publicSources);
        String payload = json.writeValueAsString(Map.of("question", question, "sources", sources));
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
        String raw;
        if (quote.enabled() && ai.getModelProvider() != AiProperties.ModelProvider.FAKE) {
            var output = research.enabled() ? model.answerWithResearch(payload) : model.answerWithQuote(payload);
            if (output.hasToolCalls()) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException();
                }
                var calls = output.getToolCalls();
                if (calls.size() == 1 && research.accepts(calls.get(0))) {
                    var request = research.parse(calls.get(0));
                    var cited = resolveCitations(request.sourceIds(), sources);
                    return new Reply("MIXED", research.answer(request, !cited.isEmpty()), cited,
                            reservation.remaining(), reservation.resetAt());
                }
                var result = quote.execute(calls);
                return new Reply(result.status(), result.text(), List.of(), reservation.remaining(), reservation.resetAt());
            }
            raw = output.getText();
        } else {
            raw = model.answer(payload);
        }

        // 模型响应也是外部输入。先限制体积和 JSON 结构，再按状态校验正文及出处。
        if (raw == null || raw.length() > 8192) {
            throw KnowledgeException.modelFailed();
        }
        JsonNode answer = json.reader()
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .readTree(raw);
        if (answer == null
                || !answer.isObject()
                || answer.size() != 3
                || !answer.path("status").isTextual()
                || !answer.path("answer").isTextual()
                || !answer.path("sourceIds").isArray()) {
            throw KnowledgeException.modelFailed();
        }

        String status = answer.get("status").textValue();
        String text = answer.get("answer").textValue().strip();
        if (text.length() > MAX_ANSWER_CHARS) {
            throw KnowledgeException.modelFailed();
        }

        // 模型只提交资料 ID，响应中的标题、正文和链接均取自本次发送的公开资料。
        var sourceIds = answer.get("sourceIds");
        List<KnowledgeCatalog.Source> cited = List.of();
        if (status.equals("ANSWERED")) {
            if (text.isBlank() || sourceIds.isEmpty()) {
                throw KnowledgeException.modelFailed();
            }
            cited = resolveCitations(sourceIds, sources);
        } else {
            if (!sourceIds.isEmpty()) {
                throw KnowledgeException.modelFailed();
            }
            // 仅通用讲解使用模型正文，资料不足、澄清和超出范围由服务端提供固定提示。
            text = switch (status) {
                case "GENERAL" -> {
                    if (text.isBlank()) {
                        throw KnowledgeException.modelFailed();
                    }
                    yield text;
                }
                case "INSUFFICIENT" -> "当前公开资料不足以回答这个问题。";
                case "CLARIFY" -> "请补充你想了解的交易主题或具体术语。";
                case "OUT_OF_SCOPE" -> "你可以问我交易机制、策略、回测或风险管理。";
                default -> throw KnowledgeException.modelFailed();
            };
        }
        return new Reply(status, text, cited, reservation.remaining(), reservation.resetAt());
    }

    /** 只允许引用本次送给模型的公开资料，普通问答和混合回答共享此规则。 */
    private static List<KnowledgeCatalog.Source> resolveCitations(
            JsonNode sourceIds, List<KnowledgeCatalog.Source> sources) {
        if (sourceIds.size() > sources.size()) {
            throw KnowledgeException.modelFailed();
        }
        var seen = new HashSet<String>();
        var cited = new ArrayList<KnowledgeCatalog.Source>(sourceIds.size());
        for (JsonNode id : sourceIds) {
            if (!id.isTextual() || !seen.add(id.textValue())) {
                throw KnowledgeException.modelFailed();
            }
            cited.add(sources.stream()
                    .filter(source -> source.id().equals(id.textValue()))
                    .findFirst()
                    .orElseThrow(KnowledgeException::modelFailed));
        }
        return List.copyOf(cited);
    }

    record Reply(
            String status,
            String answer,
            List<KnowledgeCatalog.Source> sources,
            int remaining,
            Instant resetAt) {
    }
}
