package com.quant.core.zone;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

public record ZoneVersion(@JsonDeserialize(using = StrictZoneLongDeserializer.class) Long expectedVersion) { }
