package com.quant.ai.report;

import java.util.Map;

public record VerifiedEvidence(String evidenceId, String type, Map<String, Object> detail) { }
