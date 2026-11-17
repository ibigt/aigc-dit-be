package cn.aigc.dit.server.workflow;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class WorkflowStepFingerprintTest {

    @Test
    void downstreamIgnoresPosterDurationUrlAndUpstreamFingerprint() {
        Map<String, Object> source = media("source", "users/7/a.mp4");
        source.put("inputFingerprint", "fp-1");
        source.put("reusedFromRunId", 9L);
        Map<String, Object> target = generator("target", "source");
        String first = WorkflowStepFingerprint.of(target, List.of(source, target));

        Map<String, Object> noisy = media("source", "users/7/a.mp4");
        outputOf(noisy).put("posterKey", "users/7/poster.jpg");
        outputOf(noisy).put("url", "https://cdn.example/a.mp4?sig=2");
        outputOf(noisy).put("metadata", Map.of("durationSec", 12, "ratio", "16:9"));
        noisy.put("inputFingerprint", "fp-2-because-upstream-reran");
        noisy.put("reusedFromRunId", 44L);

        assertEquals(first, WorkflowStepFingerprint.of(target, List.of(noisy, target)));

        outputOf(noisy).put("storageKey", "users/7/b.mp4");
        assertNotEquals(first, WorkflowStepFingerprint.of(target, List.of(noisy, target)));
    }

    @Test
    void selectedOutputKeyIsTheDependencyIdentity() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("nodeId", "source");
        source.put("stepType", "GENERATE");
        source.put("outputIndex", 1);
        source.put("outputs", List.of(
                Map.of("type", "video", "storageKey", "users/7/first.mp4", "posterKey", "users/7/p1.jpg"),
                Map.of("type", "video", "storageKey", "users/7/selected.mp4", "metadata", Map.of("durationSec", 8))));
        Map<String, Object> target = generator("target", "source");
        String selected = WorkflowStepFingerprint.of(target, List.of(source, target));

        source.put("outputIndex", 0);
        assertNotEquals(selected, WorkflowStepFingerprint.of(target, List.of(source, target)));
    }

    @Test
    void textDependencyTracksBodyNotLabelOrUpstreamFingerprint() {
        Map<String, Object> text = textNode("雨夜");
        text.put("inputFingerprint", "text-fp-1");
        Map<String, Object> image = generator("image", "brief");
        String first = WorkflowStepFingerprint.of(image, List.of(text, image));

        text.put("inputFingerprint", "text-fp-2");
        text.put("reusedFromRunId", 8L);
        text.put("outputs", List.of(Map.of("type", "text", "name", "说明", "value", "雨夜", "url", "ignored")));
        assertEquals(first, WorkflowStepFingerprint.of(image, List.of(text, image)));

        text.put("outputs", List.of(Map.of("type", "text", "name", "内容", "value", "晴天")));
        assertNotEquals(first, WorkflowStepFingerprint.of(image, List.of(text, image)));
    }

    @Test
    void reuseMarkerDoesNotChangeTheStepOwnHash() {
        Map<String, Object> step = generator("image", null);
        step.put("prompt", "a cat");
        String plain = WorkflowStepFingerprint.of(step, List.of(step));
        step.put("reusedFromRunId", 3L);
        step.put("creditCost", 0L);
        step.put("posterKey", "users/7/p.jpg");
        step.put("inputFingerprint", "previous");
        step.put("outputs", List.of(Map.of("storageKey", "users/7/out.png", "url", "https://cdn/x", "metadata", Map.of("durationSec", 4))));
        assertEquals(plain, WorkflowStepFingerprint.of(step, List.of(step)));

        step.put("prompt", "a dog");
        assertNotEquals(plain, WorkflowStepFingerprint.of(step, List.of(step)));
    }

    private static Map<String, Object> generator(String nodeId, String dependency) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("nodeId", nodeId);
        step.put("stepType", "GENERATE");
        step.put("prompt", "film");
        if (dependency != null) {
            step.put("dependsOn", List.of(dependency));
        }
        return step;
    }

    private static Map<String, Object> media(String nodeId, String storageKey) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("nodeId", nodeId);
        step.put("stepType", "GENERATE");
        step.put("outputIndex", 0);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("type", "video");
        output.put("storageKey", storageKey);
        step.put("outputs", List.of(output));
        return step;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> outputOf(Map<String, Object> step) {
        return (Map<String, Object>) ((List<?>) step.get("outputs")).get(0);
    }

    private static Map<String, Object> textNode(String body) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("nodeId", "brief");
        step.put("stepType", "TEXT");
        step.put("outputs", List.of(Map.of("type", "text", "name", "内容", "value", body)));
        return step;
    }
}
