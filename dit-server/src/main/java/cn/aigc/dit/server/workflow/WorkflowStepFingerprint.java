package cn.aigc.dit.server.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Stable across JSON map insertion order and database numeric representations. Never hashes client runtime. */
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
                Map<String, Object> version = new TreeMap<>();
                version.put("nodeId", id);
                version.put("fingerprint", source.get("inputFingerprint"));
                Object outputs = source.get("outputs");
                if (!"EDIT".equals(step.get("stepType")) && !"TEXT".equals(source.get("stepType"))
                        && outputs instanceof List<?> values && !values.isEmpty()) {
                    int selected = source.get("outputIndex") instanceof Number number ? number.intValue() : 0;
                    outputs = values.get(selected >= 0 && selected < values.size() ? selected : 0);
                }
                version.put("outputs", outputs);
                dependencies.add(version);
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
