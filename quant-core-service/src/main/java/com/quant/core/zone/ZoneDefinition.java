package com.quant.core.zone;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.math.BigDecimal;

public record ZoneDefinition(String marketType, String symbol, String interval,
                             BigDecimal lowerPrice, BigDecimal upperPrice,
                             @JsonDeserialize(using = StrictZoneLongDeserializer.class) Long effectiveFrom,
                             String note,
                             @JsonDeserialize(using = StrictZoneLongDeserializer.class) Long expectedVersion) { }
