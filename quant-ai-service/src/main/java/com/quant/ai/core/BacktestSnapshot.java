package com.quant.ai.core;

import com.quant.ai.core.dto.BacktestTaskDto;
import com.quant.ai.core.dto.ReplayResponseDto;

/** Whitelisted, parsed responses from the two fixed Core endpoints. */
public record BacktestSnapshot(BacktestTaskDto task, ReplayResponseDto replay) { }
