package cn.aigc.dit.server.drama.text;

import cn.aigc.dit.server.entity.AiModel;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResolvedTextModelTest {

    @Test
    void gatewayMappingPreservesAdapterConfig() {
        AiModel model = new AiModel();
        model.setId(11L);
        model.setName("兼容模型");
        model.setAdapterKey(TextAdapterCatalog.OPENAI_COMPAT);
        model.setProvider(TextAdapterCatalog.PROVIDER_CUSTOM);
        model.setEndpoint("https://example.com/v1");
        model.setModelKey("portable-model");
        model.setAdapterConfig(Map.of(
                "structuredOutput", "plain_json",
                "tokenParameter", "max_completion_tokens"));

        ResolvedTextModel resolved = TextChatGatewayImpl.toResolved(model);

        assertEquals("plain_json", resolved.adapterConfig().get("structuredOutput"));
        assertEquals("max_completion_tokens", resolved.adapterConfig().get("tokenParameter"));
    }

    @Test
    void nullAdapterOptionsAreTreatedAsUnconfigured() {
        Map<String, Object> config = new HashMap<>();
        config.put("temperature", null);
        config.put("structuredOutput", "plain_json");

        ResolvedTextModel resolved = new ResolvedTextModel(
                11L, "兼容模型", TextAdapterCatalog.OPENAI_COMPAT,
                TextAdapterCatalog.PROVIDER_CUSTOM, "https://example.com/v1", "portable-model", config);

        assertEquals(Map.of("structuredOutput", "plain_json", "enableThinking", false),
                resolved.adapterConfig());
    }

    @Test
    void missingReasoningOptionDefaultsToDisabled() {
        ResolvedTextModel resolved = new ResolvedTextModel(
                11L, "兼容模型", TextAdapterCatalog.OPENAI_COMPAT,
                TextAdapterCatalog.PROVIDER_CUSTOM, "https://example.com/v1", "portable-model", Map.of());

        assertEquals(false, resolved.adapterConfig().get("enableThinking"));
    }

    @Test
    void explicitReasoningOptionIsPreserved() {
        ResolvedTextModel resolved = new ResolvedTextModel(
                11L, "兼容模型", TextAdapterCatalog.OPENAI_COMPAT,
                TextAdapterCatalog.PROVIDER_CUSTOM, "https://example.com/v1", "portable-model",
                Map.of("enableThinking", true));

        assertEquals(true, resolved.adapterConfig().get("enableThinking"));
    }
}
