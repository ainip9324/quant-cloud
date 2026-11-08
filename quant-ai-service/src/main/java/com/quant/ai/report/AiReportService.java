package com.quant.ai.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.quant.ai.config.AiProperties;
import com.quant.ai.context.AiReportToolContext;
import com.quant.ai.context.AiRequestContext;
import com.quant.ai.context.ToolCallBudget;
import com.quant.ai.core.BacktestSnapshot;
import com.quant.ai.core.CoreGatewayClient;
import com.quant.ai.error.AiErrorCode;
import com.quant.ai.error.AiModelException;
import com.quant.ai.facts.BacktestFacts;
import com.quant.ai.facts.BacktestFactsExtractor;
import com.quant.ai.provider.ModelCallResult;
import com.quant.ai.provider.ModelClient;
import com.quant.ai.strategy.StrategySpecificationRegistry;
import com.quant.ai.tools.BacktestToolCallbacks;
import java.time.LocalDateTime;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * AI 复盘的编排入口：读取可信快照与事实、预留报告、调用模型、校验证据，最后保存结果。
 * 数据库事务只用于预留报告；外部模型调用不持有这段事务的行锁。
 */
@Service
public class AiReportService {
    private static final String SCHEMA_VERSION = "1";
    private final CoreGatewayClient core;
    private final BacktestFactsExtractor factsExtractor;
    private final StrategySpecificationRegistry specifications;
    private final BacktestToolCallbacks callbacks;
    private final ModelClient model;
    private final AiReportRepository repository;
    private final EvidenceVerifier evidenceVerifier;
    private final CanonicalJsonHasher hasher;
    private final ObjectMapper json;
    private final AiReportProperties properties;
    private final AiProperties aiProperties;
    private final TransactionTemplate transactionTemplate;
    private final PromptBuilder prompts;
    private final ReportSchemaValidator schemaValidator;

    public AiReportService(CoreGatewayClient core, BacktestFactsExtractor factsExtractor,
                           StrategySpecificationRegistry specifications, BacktestToolCallbacks callbacks,
                           ModelClient model, AiReportRepository repository, EvidenceVerifier evidenceVerifier,
                           CanonicalJsonHasher hasher, ObjectMapper json, AiReportProperties properties,
                           AiProperties aiProperties, TransactionTemplate transactionTemplate,
                           PromptBuilder prompts, ReportSchemaValidator schemaValidator) {
        this.core = core;
        this.factsExtractor = factsExtractor;
        this.specifications = specifications;
        this.callbacks = callbacks;
        this.model = model;
        this.repository = repository;
        this.evidenceVerifier = evidenceVerifier;
        this.hasher = hasher;
        this.json = json;
        this.properties = properties;
        this.aiProperties = aiProperties;
        this.transactionTemplate = transactionTemplate;
        this.prompts = prompts;
        this.schemaValidator = schemaValidator;
    }

    /**
     * 同步创建一次复盘。普通创建可以复用同指纹的成功报告；regenerate 跳过这项复用，
     * 但同指纹若已有 GENERATING，仍返回该记录，由 Controller 响应 409。
     * userId 来自认证主体，原始 Q-Token 仅随请求上下文传给受控的 core 读取链。
     */
    public CreationResult create(long userId, String qToken, long taskId, boolean regenerate) {
        if (taskId < 1)
            throw new AiModelException(AiErrorCode.AI_TOOL_INVALID_ARGUMENT, "taskId must be positive");

        // 1. 从 core 读取当前用户可访问的任务及持久化回放；确定性事实由服务端计算，不交给模型猜测。
        AiRequestContext request = AiRequestContext.authenticated(userId, qToken);
        BacktestSnapshot snapshot = core.getSnapshot(request, taskId);
        BacktestFacts facts = factsExtractor.extract(snapshot.task(), snapshot.replay());
        String version = specifications.currentVersion(snapshot.task().strategyId());
        Map<String, Object> specification = specifications.get(snapshot.task().strategyId(), version);

        // 2. 输入、提示词、Schema、策略规范及模型身份共同决定复用范围。
        // 只比较 taskId 不够：同一任务的事实、提示词或模型变化后，不能沿用旧报告。
        String inputHash = hasher.hash(Map.of("task", snapshot.task(), "replay", snapshot.replay(), "facts", facts));
        String promptHash = hasher.hash(Map.of("system", prompts.template(), "schema", prompts.schema()));
        String specHash = hasher.hash(specification);
        String provider = aiProperties.getModelProvider().name().toLowerCase();
        String modelName = "deepseek".equals(provider) ? aiProperties.getModelName() : "fake";
        AiReportRepository.Fingerprint fingerprint = new AiReportRepository.Fingerprint(
                userId, taskId, inputHash, prompts.version(), promptHash,
                SCHEMA_VERSION, version, specHash, provider, modelName
        );

        // 3. 短事务内处理查重和 attempt 预留，返回后事务已结束；复用/生成中均不再调用模型。
        Reservation reservation = reserve(fingerprint, regenerate);
        if (reservation.reused() || reservation.generating())
            return new CreationResult(reservation.record(), reservation.reused());

        long started = System.nanoTime();
        try {
            // 4. 首次生成与修复共享同一工具预算、任务范围和截止时间，修复不会获得一份新额度。
            ToolCallBudget budget = new ToolCallBudget(properties.toolCallBudget());
            AiReportToolContext toolContext = new AiReportToolContext(
                    request, taskId, snapshot.task().strategyId(), version, budget
            );
            long reportDeadlineNanos = started + properties.totalTimeout().toNanos();
            /*
            最重要的部分，实际调用LLMapi的地方
            prompts.system()	系统提示词模板：告诉模型怎么分析、哪些内容不能编造
            prompts.user(facts)	本次回测的事实 JSON，加上报告输出的 JSON Schema
            toolContext	给服务端工具回调用的身份、任务范围和预算，不是提示词正文
            reportDeadlineNanos	刚算出的本次生成截止点
             */
            ModelCallResult call = callModel(prompts.system(), prompts.user(facts), toolContext, reportDeadlineNanos);
            ModelCallResult firstCall = call;
            AiReviewReport report;

            // 5. 先检查输出是否完整，再验证 JSON/Schema、解析证据并组装服务端报告。
            try {
                ensureWithinTotalDeadline(started, call);
                //把模型返回的候选文本，经过格式校验、证据核验和引用检查，再与服务端已有的回测事实一起组装成 AiReviewReport
                report = buildReport(taskId, facts, snapshot, version, call.content());
            } catch (AiModelException invalidOutput) {
                // 只有 OUTPUT_INVALID 允许补一次修复；证据错误等其它业务异常直接进入失败收口。
                if (invalidOutput.getCode() != AiErrorCode.AI_REPORT_OUTPUT_INVALID)
                    throw invalidOutput;

                ensureRepairTimeRemaining(reportDeadlineNanos);
                call = callModel(
                        prompts.system(),
                        prompts.repairUser(facts, repairReason(invalidOutput), safeCandidate(call.content())),
                        toolContext,
                        reportDeadlineNanos
                );
                ensureWithinTotalDeadline(started, call);
                // 修复结果走相同校验；此处再失败会交给外层 catch，不会形成无限重试。
                report = buildReport(taskId, facts, snapshot, version, call.content());
            }

            // 6. 有修复时累计两次 token 用量；实际模型/结束原因取最终响应，工具次数取共享预算。
            ModelCallResult audited = new ModelCallResult(
                    call.content(), call.provider(), call.model(),
                    sum(firstCall.promptTokens(), call == firstCall ? null : call.promptTokens()),
                    sum(firstCall.completionTokens(), call == firstCall ? null : call.completionTokens()),
                    sum(firstCall.totalTokens(), call == firstCall ? null : call.totalTokens()),
                    call.finishReason(),
                    (System.nanoTime() - started) / 1_000_000,
                    budget.used()
            );

            // 7. 入库的是包含可信事实与已验证证据的完整报告，不能直接保存模型原始 JSON。
            String reportJson = json.writeValueAsString(report);
            if (reportJson.getBytes(StandardCharsets.UTF_8).length > properties.persistedReportByteLimit())
                throw new AiModelException(AiErrorCode.AI_REPORT_OUTPUT_INVALID, "report too large");

            // 条件更新只推进仍为 GENERATING 的记录，避免覆盖超时回收或其它请求已写入的终态。
            if (!repository.markSuccess(reservation.record().id(), reportJson, audited)) {
                throw new AiModelException(AiErrorCode.AI_REPORT_DATABASE_ERROR, "report state changed");
            }
            return new CreationResult(require(reservation.record().id(), userId), false);
        } catch (AiModelException ex) {
            // 保留业务错误码，但数据库只写固定安全文案，不保存模型原文或底层异常细节。
            repository.markFailed(reservation.record().id(), ex.getCode().name(), "report generation failed");
            throw ex;
        } catch (Exception ex) {
            // 沿用现有兜底契约；markFailed 本身也只更新 GENERATING，不覆盖已有终态。
            repository.markFailed(reservation.record().id(), AiErrorCode.AI_REPORT_OUTPUT_INVALID.name(), "report generation failed");
            throw new AiModelException(AiErrorCode.AI_REPORT_OUTPUT_INVALID, "report generation failed");
        }
    }

    /** 先处理过期生成记录，再按用户归属读取报告。 */
    public AiReportRecord get(long userId, long reportId) {
        expireStale();
        return require(reportId, userId);
    }

    /** 组装报告详情，只有成功记录才反序列化报告正文。 */
    public AiReportDetailVO detail(long userId, long reportId) {
        AiReportRecord record = get(userId, reportId);
        try {
            AiReviewReport report = record.status() == ReportStatus.SUCCESS
                    ? json.readValue(record.reportJson(), AiReviewReport.class) : null;
            return new AiReportDetailVO(
                    record.id(), record.taskId(), record.status(), record.attemptNo(),
                    record.modelName(), record.actualModelName(), record.promptVersion(),
                    record.createTime(), record.updateTime(), report, record.errorCode()
            );
        } catch (Exception ex) {
            throw new AiModelException(AiErrorCode.AI_REPORT_DATABASE_ERROR, "stored report is invalid");
        }
    }

    /** 校验分页参数并查询指定用户和任务的报告摘要。 */
    public List<AiReportListItemVO> listView(long userId, long taskId, int page, int size) {
        if (taskId < 1 || page < 1 || size < 1 || size > 100)
            throw new AiModelException(AiErrorCode.AI_TOOL_INVALID_ARGUMENT, "invalid page");
        expireStale();
        return repository.listProjection(userId, taskId, (page - 1) * size, size);
    }

    /** 模型只提供分析草稿和证据引用；最终事实及证据详情由服务端回填。 */
    private AiReviewReport buildReport(long taskId, BacktestFacts facts, BacktestSnapshot snapshot,
                                       String version, String content) {
        JsonNode validated = schemaValidator.validate(content);//结构校验
        Map<String, Object> candidate = json.convertValue(validated, new com.fasterxml.jackson.core.type.TypeReference<>() { });
        String headline = text(candidate.get("headline"));
        String summary = text(candidate.get("summary"));
        if (headline == null || summary == null)
            throw new AiModelException(AiErrorCode.AI_REPORT_OUTPUT_INVALID, "required fields missing");

        // 证据必须能在这次快照、确定性事实或指定版本的策略规范中找到，不能直接信任模型填写的详情。
        List<CandidateEvidence> evidence = json.convertValue(candidate.get("evidence"), new com.fasterxml.jackson.core.type.TypeReference<>() { });
        List<VerifiedEvidence> verified = evidenceVerifier.verify(evidence, snapshot, facts, version);

        // 分析段落只能引用上面已验证的 evidenceId；实验假设固定保持 PENDING_VALIDATION。
        List<AiReviewReport.ReportItem> findings = items(candidate.get("findings"), verified);
        List<AiReviewReport.ReportItem> warnings = items(candidate.get("riskWarnings"), verified);
        List<AiReviewReport.ExperimentHypothesis> hypotheses = hypotheses(candidate.get("experimentHypotheses"), verified);
        return new AiReviewReport(
                SCHEMA_VERSION, taskId, facts, headline, summary,
                findings, warnings, hypotheses, verified
        );
    }

    /** 拒绝超出报告总时限或被模型截断的输出。 */
    private void ensureWithinTotalDeadline(long started, ModelCallResult call) {
        // 即使文本恰好能解析成 JSON，length 也表示被模型截断，不能当完整报告接收。
        if ((System.nanoTime() - started) > properties.totalTimeout().toNanos()
                || "length".equalsIgnoreCase(call.finishReason())) {
            throw new AiModelException(AiErrorCode.AI_REPORT_OUTPUT_INVALID, "model output was incomplete");
        }
    }

    /** 在发起修复前确认总时限还有剩余。 */
    private void ensureRepairTimeRemaining(long deadlineNanos) {
        if (System.nanoTime() >= deadlineNanos) {
            throw new AiModelException(AiErrorCode.AI_MODEL_TIMEOUT, "report generation timed out");
        }
    }

    /** 只把本次剩余时间交给模型边界；提供方还会与单次 modelTimeout 取更小值。 */
    private ModelCallResult callModel(String system, String user, AiReportToolContext context, long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0)
            throw new AiModelException(AiErrorCode.AI_MODEL_TIMEOUT, "report generation timed out");

        return model.completeReviewJson(
                system, user, callbacks.callbacks(), callbacks.toolContext(context),
                Duration.ofNanos(remaining)
        );
    }

    /** 传给修复提示词的是有限错误标签，而不是可能包含内部信息的异常原文。 */
    private static String repairReason(AiModelException exception) {
        return switch (exception.getCode()) {
            case AI_REPORT_EVIDENCE_INVALID -> "EVIDENCE_INVALID";
            case AI_REPORT_OUTPUT_INVALID -> "SCHEMA_INVALID";
            default -> "JSON_INVALID";
        };
    }

    /** 截取待修复的候选原文，限制送回模型的文本长度。 */
    private static String safeCandidate(String candidate) {
        // 这里只截取最多4096个字符；PromptBuilder仍把候选输出标为不可信数据。
        if (candidate == null)
            return "";
        return candidate.length() <= 4096 ? candidate : candidate.substring(0, 4096);
    }

    /** 累计首次生成和修复的用量，两次均未知时保留空值。 */
    private static Integer sum(Integer first, Integer second) {
        // 两次都没返回用量时保留null，不能把“未知”记成0 token。
        return first == null && second == null
                ? null : (first == null ? 0 : first) + (second == null ? 0 : second);
    }

    /** 组装发现或风险段落，要求每项引用至少一条已验证证据。 */
    private List<AiReviewReport.ReportItem> items(Object raw, List<VerifiedEvidence> evidence) {
        List<AiReviewReport.ReportItem> result = new ArrayList<>();
        for (Map<String, Object> item : json.convertValue(
                raw == null ? List.of() : raw,
                new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() { }
        )) {
            List<String> ids = stringList(item.get("evidenceIds"));
            if (ids.isEmpty() || ids.stream().anyMatch(id -> evidence.stream().noneMatch(value -> value.evidenceId().equals(id))))
                throw new AiModelException(AiErrorCode.AI_REPORT_OUTPUT_INVALID, "analysis lacks verified evidence");
            result.add(new AiReviewReport.ReportItem(text(item.get("title")), text(item.get("detail")), ids));
        }
        return result;
    }

    /** 组装待验证的实验假设，并校验给出的证据引用。 */
    private List<AiReviewReport.ExperimentHypothesis> hypotheses(Object raw, List<VerifiedEvidence> evidence) {
        List<AiReviewReport.ExperimentHypothesis> result = new ArrayList<>();
        for (Map<String, Object> item : json.convertValue(
                raw == null ? List.of() : raw,
                new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() { }
        )) {
            List<String> ids = stringList(item.get("evidenceIds"));
            if (!"PENDING_VALIDATION".equals(item.get("status")) || ids.stream().anyMatch(id -> evidence.stream().noneMatch(value -> value.evidenceId().equals(id))))
                throw new AiModelException(AiErrorCode.AI_REPORT_OUTPUT_INVALID, "invalid hypothesis");
            result.add(new AiReviewReport.ExperimentHypothesis(text(item.get("hypothesis")), "PENDING_VALIDATION", ids));
        }
        return result;
    }

    /** 预留阶段单独使用短事务，避免模型响应期间一直持有查询锁。 */
    private Reservation reserve(AiReportRepository.Fingerprint fingerprint, boolean regenerate) {
        return transactionTemplate.execute(status -> reserveLocked(fingerprint, regenerate));
    }

    /** 在锁内判断复用、生成中冲突或新占位，处理并发插入争用。 */
    private Reservation reserveLocked(AiReportRepository.Fingerprint fingerprint, boolean regenerate) {
        expireStale();
        AiReportRecord latest = repository.findLatestForUpdate(fingerprint);

        // 生成中的同指纹请求优先返回，regenerate也不能与它并行启动第二份生成。
        if (latest != null && latest.status() == ReportStatus.GENERATING)
            return new Reservation(latest, false, true);

        AiReportRecord latestSuccess = repository.findLatestSuccessForUpdate(fingerprint);
        if (!regenerate && latestSuccess != null)
            return new Reservation(latestSuccess, true, false);

        // 失败重试或显式重新生成使用下一次attempt；历史成功/失败记录不被删除。
        int attempt = latest == null ? 1 : latest.attemptNo() + 1;
        try {
            long id = repository.insertGenerating(fingerprint, attempt);
            return new Reservation(require(id, fingerprint.userId()), false, false);
        } catch (DuplicateKeyException ex) {
            // 并发争用同一attempt时读回竞争记录；这里不另分配attempt，也不重新写入。
            // 调用方按reused/generating决定早退；其它状态仍沿用原生成分支。
            AiReportRecord concurrent = repository.findLatestForUpdate(fingerprint);
            return new Reservation(
                    concurrent,
                    concurrent.status() == ReportStatus.SUCCESS,
                    concurrent.status() == ReportStatus.GENERATING
            );
        }
    }

    /** 按报告总时限与宽限期回收过期的生成占位。 */
    private void expireStale() {
        // 预留和读取都会触发回收；这里只标记过期GENERATING，不负责中断旧模型线程。
        repository.failStale(LocalDateTime.now().minus(properties.totalTimeout()).minus(properties.staleGrace()));
    }

    /** 按用户归属读取报告，缺失时返回统一的未找到错误。 */
    private AiReportRecord require(long reportId, long userId) {
        AiReportRecord record = repository.findById(reportId, userId);
        if (record == null)
            throw new AiModelException(AiErrorCode.AI_REPORT_NOT_FOUND, "report not found");
        return record;
    }

    private static String text(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list))
            return List.of();
        return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    public record CreationResult(AiReportRecord record, boolean reused) {
    }

    private record Reservation(AiReportRecord record, boolean reused, boolean generating) {
    }
}
