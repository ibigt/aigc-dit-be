package cn.aigc.dit.server.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Stable across JSON map insertion order and database numeric representations. Never hashes client runtime.
 * {@code reusedFromRunId} is display-only and is not part of the hash. A dependency contributes its node id
 * and the selected output storage key; text nodes contribute their body. Poster, duration, url and the
 * upstream fingerprint string are not part of the downstream hash, so a metadata-only change cannot
 * invalidate every later node.
 */
public final class WorkflowStepFingerprint {
    private static final ObjectMapper JSON = new ObjectMapper();
    private WorkflowStepFingerprint() { }

    public static String of(Map<String, Object> step, List<Map<String, Object>> steps) {
        Map<String, Object> inputs = new TreeMap<>(step);
        for (String field : List.of("title", "status", "progress", "creditCost", "taskId", "error",
                "inputFingerprint", "reusedFromRunId", "outputIndex", "outputStorageKey", "posterKey")) inputs.remove(field);
        if (!List.of("TEXT", "ASSET").contains(step.get("stepType"))) inputs.remove("outputs");
        List<Object> dependencies = new ArrayList<>();
        if (step.get("dependsOn") instanceof List<?> ids) {
            for (Object id : ids) {
                Map<String, Object> source = steps.stream().filter(candidate -> Objects.equals(id, candidate.get("nodeId")))
                        .findFirst().orElseThrow();
                dependencies.add(dependencyVersion(id, source));
            }
        }
        inputs.put("dependencyVersions", dependencies);
        try {
            byte[] bytes = JSON.writeValueAsString(canonical(inputs)).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception error) {
            throw new IllegalStateException("无法计算工作流输入版本", error);
        }
    }

    /**
     * Identity of one upstream node as seen by a downstream step.
     * Media nodes contribute the storage key of the output selected by {@code outputIndex}
     * (the file the next node actually consumes). Text nodes contribute the body values.
     * Reuse markers, posters, durations, urls and the upstream fingerprint are omitted.
     */
    private static Map<String, Object> dependencyVersion(Object nodeId, Map<String, Object> source) {
        Map<String, Object> version = new TreeMap<>();
        version.put("nodeId", nodeId);
        if ("TEXT".equals(source.get("stepType"))) {
            version.put("text", textBody(source));
        } else {
            version.put("storageKey", selectedStorageKey(source));
        }
        return version;
    }

    private static String selectedStorageKey(Map<String, Object> source) {
        if (!(source.get("outputs") instanceof List<?> values) || values.isEmpty()) {
            return null;
        }
        int selected = source.get("outputIndex") instanceof Number number ? number.intValue() : 0;
        int index = selected >= 0 && selected < values.size() ? selected : 0;
        Object chosen = values.get(index);
        if (!(chosen instanceof Map<?, ?> output)) {
            return null;
        }
        Object key = output.get("storageKey");
        return key == null ? null : String.valueOf(key);
    }

    /** Text outputs are {@code {type, name, value}}; only the body is part of a downstream hash. */
    private static List<String> textBody(Map<String, Object> source) {
        if (!(source.get("outputs") instanceof List<?> outputs)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object item : outputs) {
            if (item instanceof Map<?, ?> map && map.get("value") != null) {
                values.add(String.valueOf(map.get("value")));
            }
        }
        return values;
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), canonical(item)));
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(WorkflowStepFingerprint::canonical).toList();
        if (value instanceof Number number) return new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
        return value;
    }
}
