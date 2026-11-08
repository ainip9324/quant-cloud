package com.quant.core.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;

/** Accept only a JSON integer (or null) for the backtest bar limit. */
public final class StrictBacktestLimitDeserializer extends JsonDeserializer<Integer> {
    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() == JsonToken.VALUE_NULL) {
            return null;
        }
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
            throw context.weirdStringException(parser.getValueAsString(), Integer.class,
                    "limit 必须为整数");
        }
        try {
            return parser.getIntValue();
        } catch (Exception e) {
            throw context.weirdStringException(parser.getText(), Integer.class, "limit 超出整数范围");
        }
    }
}
