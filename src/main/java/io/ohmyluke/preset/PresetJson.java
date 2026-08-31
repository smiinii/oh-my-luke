package io.ohmyluke.preset;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;

/** Strict bounded JSON. Never echo the parser exception, which can contain source text. */
public final class PresetJson {
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();
    static {
        for (CoercionInputShape shape : new CoercionInputShape[] {
                CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean}) {
            MAPPER.coercionConfigFor(LogicalType.Textual).setCoercion(shape, CoercionAction.Fail);
        }
    }

    private PresetJson() {}

    public static String encode(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalArgumentException("cannot encode preset data"); }
    }

    public static <T> T decode(String value, Class<T> type) {
        if (value == null || value.length() > 512 * 1024) {
            throw new IllegalArgumentException("preset JSON exceeds limit");
        }
        try {
            T result = MAPPER.readValue(value, type);
            if (result == null) { throw new IllegalArgumentException("preset JSON must not be null"); }
            return result;
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("invalid preset JSON or unsupported fields");
        }
    }
}
