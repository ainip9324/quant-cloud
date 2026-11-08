package com.quant.ai.report;

import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 报告状态持久化；带ForUpdate的方法由Service的预留事务调用。 */
@Repository
public class AiReportRepository {
    private final JdbcTemplate jdbc;

    public AiReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 始终同时按报告id和本人userId读取，报告id本身不能作为访问凭据。 */
    public AiReportRecord findById(long id, long userId) {
        List<AiReportRecord> rows = jdbc.query(
                "select * from ai_report where id=? and user_id=?", mapper(), id, userId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 按用户和任务分页查询摘要字段，省略报告正文。 */
    public List<AiReportListItemVO> listProjection(long userId, long taskId, int offset, int size) {
        return jdbc.query("select id,task_id,status,attempt_no,model_name,actual_model_name,prompt_version,create_time,update_time from ai_report where user_id=? and task_id=? order by create_time desc,id desc limit ? offset ?",
                (rs, row) -> new AiReportListItemVO(rs.getLong("id"), rs.getLong("task_id"),
                        ReportStatus.valueOf(rs.getString("status")), rs.getInt("attempt_no"),
                        rs.getString("model_name"), rs.getString("actual_model_name"), rs.getString("prompt_version"),
                        rs.getTimestamp("create_time").toLocalDateTime(), rs.getTimestamp("update_time").toLocalDateTime()),
                userId, taskId, size, offset);
    }

    /** 在预留事务中锁定同指纹的最新一次生成记录。 */
    public AiReportRecord findLatestForUpdate(Fingerprint fingerprint) {
        // 锁定同指纹最新attempt，供生成中判断及下一次attempt编号使用。
        List<AiReportRecord> rows = jdbc.query("select * from ai_report where user_id=? and task_id=? and input_hash=? and prompt_version=? and prompt_hash=? and report_schema_version=? and specification_version=? and specification_hash=? and model_provider=? and model_name=? order by attempt_no desc limit 1 for update", mapper(),
                fingerprint.userId(), fingerprint.taskId(), fingerprint.inputHash(),
                fingerprint.promptVersion(), fingerprint.promptHash(), fingerprint.schemaVersion(),
                fingerprint.specificationVersion(), fingerprint.specificationHash(),
                fingerprint.modelProvider(), fingerprint.modelName()
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 锁定同指纹的最新成功记录，供普通创建复用。 */
    public AiReportRecord findLatestSuccessForUpdate(Fingerprint fingerprint) {
        // 最新attempt可能失败；普通创建仍允许复用更早的同指纹SUCCESS。
        List<AiReportRecord> rows = jdbc.query("select * from ai_report where user_id=? and task_id=? and input_hash=? and prompt_version=? and prompt_hash=? and report_schema_version=? and specification_version=? and specification_hash=? and model_provider=? and model_name=? and status='SUCCESS' order by attempt_no desc limit 1 for update", mapper(),
                fingerprint.userId(), fingerprint.taskId(), fingerprint.inputHash(),
                fingerprint.promptVersion(), fingerprint.promptHash(), fingerprint.schemaVersion(),
                fingerprint.specificationVersion(), fingerprint.specificationHash(),
                fingerprint.modelProvider(), fingerprint.modelName()
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 插入生成占位记录并返回当前连接取得的记录 ID。 */
    public long insertGenerating(Fingerprint f, int attemptNo) {
        // 先预留GENERATING再请求模型；唯一键冲突由Service读回竞争记录处理。
        jdbc.update("insert into ai_report(user_id,task_id,status,input_hash,prompt_version,prompt_hash,report_schema_version,specification_version,specification_hash,model_provider,model_name,attempt_no,create_time,update_time) values(?,?, 'GENERATING',?,?,?,?,?,?,?,?,?,now(),now())",
                f.userId(), f.taskId(), f.inputHash(), f.promptVersion(), f.promptHash(),
                f.schemaVersion(), f.specificationVersion(), f.specificationHash(),
                f.modelProvider(), f.modelName(), attemptNo
        );
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    /** 仅将生成中的记录更新为成功，同时保存报告及模型用量。 */
    public boolean markSuccess(long id, String reportJson, com.quant.ai.provider.ModelCallResult result) {
        // 只允许GENERATING -> SUCCESS；返回false表示记录已先被回收或推进，不能覆盖终态。
        return jdbc.update("update ai_report set status='SUCCESS',report_json=cast(? as json),actual_model_name=?,prompt_tokens=?,completion_tokens=?,total_tokens=?,finish_reason=?,latency_ms=?,tool_call_count=?,error_code=null,error_message=null,update_time=now() where id=? and status='GENERATING'",
                reportJson, result.model(), result.promptTokens(), result.completionTokens(),
                result.totalTokens(), result.finishReason(), result.latencyMs(), result.toolCallCount(), id
        ) == 1;
    }

    /** 仅将生成中的记录标记为失败并保存错误信息。 */
    public boolean markFailed(long id, String code, String message) {
        // 与成功写入同样限制原状态；异常收口不会把已成功的报告改成FAILED。
        return jdbc.update("update ai_report set status='FAILED',error_code=?,error_message=?,update_time=now() where id=? and status='GENERATING'",
                code, trim(message), id) == 1;
    }

    /** 批量标记超过截止时间的生成占位记录，并返回更新数量。 */
    public int failStale(LocalDateTime cutoff) {
        // 这里只回收数据库中的过期占位；旧请求即使晚到，也无法再通过markSuccess的状态条件。
        return jdbc.update("update ai_report set status='FAILED',error_code='AI_REPORT_STALE',error_message='report generation expired',update_time=now() where status='GENERATING' and update_time < ?", cutoff);
    }

    /** 补齐缺失错误提示并将其限制在数据库字段允许的长度。 */
    private static String trim(String message) {
        if (message == null)
            return "report generation failed";
        return message.length() <= 512 ? message : message.substring(0, 512);
    }

    /** 将报告表记录转换为服务内部对象，保留可空的模型用量。 */
    private static RowMapper<AiReportRecord> mapper() {
        return (ResultSet rs, int row) -> new AiReportRecord(
                rs.getLong("id"), rs.getLong("user_id"), rs.getLong("task_id"),
                ReportStatus.valueOf(rs.getString("status")),
                rs.getString("input_hash"), rs.getString("prompt_version"), rs.getString("prompt_hash"),
                rs.getString("report_schema_version"), rs.getString("specification_version"),
                rs.getString("specification_hash"), rs.getString("model_provider"),
                rs.getString("model_name"), rs.getString("actual_model_name"), rs.getInt("attempt_no"),
                rs.getString("report_json"),
                (Integer) rs.getObject("prompt_tokens"), (Integer) rs.getObject("completion_tokens"),
                (Integer) rs.getObject("total_tokens"), rs.getString("finish_reason"),
                (Long) rs.getObject("latency_ms"), (Integer) rs.getObject("tool_call_count"),
                rs.getString("error_code"), rs.getString("error_message"),
                rs.getTimestamp("create_time").toLocalDateTime(),
                rs.getTimestamp("update_time").toLocalDateTime()
        );
    }

    public record Fingerprint(long userId, long taskId, String inputHash, String promptVersion,
                              String promptHash, String schemaVersion, String specificationVersion,
                              String specificationHash, String modelProvider, String modelName) { }
}
