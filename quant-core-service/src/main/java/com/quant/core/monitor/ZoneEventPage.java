package com.quant.core.monitor;

import java.util.List;

public record ZoneEventPage(List<ZoneEvent> records, long total, int page, int size) { }
