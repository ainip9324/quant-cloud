package com.quant.ai.report;

import com.quant.ai.facts.BacktestFacts;
import java.util.List;

/** Persisted report: deterministic facts and AI analysis are intentionally separate. */
public record AiReviewReport(
        String schemaVersion,
        long taskId,
        BacktestFacts deterministicFacts,
        String headline,
        String summary,
        List<ReportItem> findings,
        List<ReportItem> riskWarnings,
        List<ExperimentHypothesis> experimentHypotheses,
        List<VerifiedEvidence> verifiedEvidence) {

    public record ReportItem(String title, String detail, List<String> evidenceIds) { }
    public record ExperimentHypothesis(String hypothesis, String status, List<String> evidenceIds) { }
}
