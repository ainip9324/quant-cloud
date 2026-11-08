package com.quant.core.zone;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import java.io.IOException;

/** Versions and epoch milliseconds accept JSON integers only. */
public final class StrictZoneLongDeserializer extends JsonDeserializer<Long> {
    @Override public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
            throw JsonMappingException.from(parser, "Expected a JSON integer");
        }
        return parser.getLongValue();
    }
}
