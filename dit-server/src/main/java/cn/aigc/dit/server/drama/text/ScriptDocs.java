package cn.aigc.dit.server.drama.text;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.Map;

/**
 * ScriptDoc 与 JSONB Map 互转；锁定前校验是否有可入库内容。
 */
public final class ScriptDocs {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private ScriptDocs() {
    }

    public static Map<String, Object> toMap(ScriptDoc doc) {
        if (doc == null) {
            return null;
        }
        return MAPPER.convertValue(doc, MAP);
    }

    public static ScriptDoc fromMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        return MAPPER.convertValue(map, ScriptDoc.class);
    }

    public static boolean hasScenes(Map<String, Object> script) {
        if (script == null) {
            return false;
        }
        Object scenes = script.get("scenes");
        return scenes instanceof Collection<?> collection && !collection.isEmpty();
    }

    public static boolean lockable(String sourceText, Map<String, Object> script) {
        return StringUtils.hasText(sourceText) || hasScenes(script);
    }
}
