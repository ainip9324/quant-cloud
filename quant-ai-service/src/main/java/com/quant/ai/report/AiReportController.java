package com.quant.ai.report;

import com.quant.ai.context.AiRequestContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestAttribute;
import com.quant.ai.security.AiRequestAuthFilter;
import com.quant.common.auth.AuthenticatedPrincipal;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/reports")
public class AiReportController {
    private final AiReportService reports;

    public AiReportController(AiReportService reports) {
        this.reports = reports;
    }

    /** 同步生成入口：身份来自认证主体，Q-Token供服务端继续按本人权限读取core。 */
    @PostMapping
    public ResponseEntity<?> create(@RequestAttribute(AiRequestAuthFilter.PRINCIPAL_ATTRIBUTE) AuthenticatedPrincipal principal,
                                    @RequestHeader("Q-Token") String qToken,
                                    @Valid @RequestBody CreateAiReportRequest request) {
        AiReportService.CreationResult result = reports.create(principal.userId(), qToken, request.taskId(), false);
        // GENERATING表示另一个请求已预留同一报告，不是本接口把任务投进了后台队列。
        if (result.record().status() == ReportStatus.GENERATING) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "code", "AI_REPORT_GENERATING", "reportId", result.record().id()
            ));
        }
        // 普通创建复用成功报告返回200；本次新生成返回201。
        return ResponseEntity.status(result.reused() ? HttpStatus.OK : HttpStatus.CREATED).body(
                new AiReportCreationVO(
                        result.record().id(), result.record().taskId(), result.record().status(),
                        result.record().attemptNo(), result.reused()
                )
        );
    }

    /** 先按本人归属读取旧报告，再沿同一create链对其taskId请求重新生成。 */
    @PostMapping("/{reportId}/regenerate")
    public ResponseEntity<?> regenerate(@RequestAttribute(AiRequestAuthFilter.PRINCIPAL_ATTRIBUTE) AuthenticatedPrincipal principal,
                                        @RequestHeader("Q-Token") String qToken,
                                        @PathVariable @Positive long reportId) {
        AiReportRecord old = reports.get(principal.userId(), reportId);
        AiReportService.CreationResult result = reports.create(principal.userId(), qToken, old.taskId(), true);
        if (result.record().status() == ReportStatus.GENERATING)
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "code", "AI_REPORT_GENERATING", "reportId", result.record().id()
            ));

        // 沿用重新生成接口的201/reused=false响应约定，预留并发分支仍由Service处理。
        return ResponseEntity.status(HttpStatus.CREATED).body(
                new AiReportCreationVO(
                        result.record().id(), result.record().taskId(), result.record().status(),
                        result.record().attemptNo(), false
                )
        );
    }

    /** 按认证主体读取指定复盘报告的详情。 */
    @GetMapping("/{reportId}")
    public AiReportDetailVO get(@RequestAttribute(AiRequestAuthFilter.PRINCIPAL_ATTRIBUTE) AuthenticatedPrincipal principal, @PathVariable @Positive long reportId) {
        return reports.detail(principal.userId(), reportId);
    }

    /** 按认证主体与回测任务分页读取复盘列表。 */
    @GetMapping
    public List<AiReportListItemVO> list(@RequestAttribute(AiRequestAuthFilter.PRINCIPAL_ATTRIBUTE) AuthenticatedPrincipal principal,
                                     @RequestParam @Positive long taskId,
                                     @RequestParam(defaultValue = "1") @Min(1) int page,
                                     @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return reports.listView(principal.userId(), taskId, page, size);
    }
}
