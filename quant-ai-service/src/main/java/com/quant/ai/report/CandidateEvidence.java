package com.quant.ai.report;

/** Model-proposed reference only. Server resolves every final detail. */
public record CandidateEvidence(String candidateId, String type, Long sequence, String factCode, String sourceId,
                                String strategyId, String version, String ruleCode) { }
