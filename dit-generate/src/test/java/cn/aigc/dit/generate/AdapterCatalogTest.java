package cn.aigc.dit.generate;

import cn.aigc.dit.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdapterCatalogTest {

    @Test
    void infersFromProviderAndCategory() {
        assertEquals(AdapterCatalog.AGNES_IMAGE_2_5, AdapterCatalog.infer("官方API", "image", "agnes-image-2.5-flash"));
        assertEquals(AdapterCatalog.AGNES_VIDEO_2_5, AdapterCatalog.infer("官方API", "video", "agnes-video-2.5-flash"));
        assertEquals(AdapterCatalog.WAN3, AdapterCatalog.infer("官方API", "video", "wan3.0-video"));
        assertEquals(AdapterCatalog.WAN3, AdapterCatalog.infer("DashScope", "video", "wan3.0-video"));
        assertEquals(AdapterCatalog.TRANSPORT_DASHSCOPE, AdapterCatalog.transport(AdapterCatalog.WAN3, "官方API"));
        assertEquals(AdapterCatalog.WAN3_VIDEO_MODEL, AdapterCatalog.defaultModelKey(AdapterCatalog.WAN3));
        assertTrue(AdapterCatalog.isDashScope(AdapterCatalog.WAN3));
        assertEquals(AdapterCatalog.MINIMAX_H3, AdapterCatalog.infer("官方API", "video", "MiniMax-H3"));
        assertEquals(AdapterCatalog.MINIMAX_H3, AdapterCatalog.infer("ComfyUI", "video", "minimax.h3"));
        assertEquals(AdapterCatalog.SEEDANCE_2, AdapterCatalog.infer("RunningHub", "video", "seedance-2.0"));
        assertEquals(AdapterCatalog.SEEDANCE_2_5, AdapterCatalog.infer("官方API", "video", "doubao-seedance-2-5-260628"));
        assertEquals(AdapterCatalog.SEEDANCE_2_5, AdapterCatalog.infer("火山方舟", "video", "seedance-2.5"));
        assertEquals(AdapterCatalog.SEEDANCE_2_0, AdapterCatalog.infer("官方API", "video", "doubao-seedance-2-0-fast-260128"));
        assertEquals(AdapterCatalog.SEEDANCE_2_0, AdapterCatalog.infer("官方API", "video", "doubao-seedance-2.0-fast"));
        assertEquals(AdapterCatalog.SEEDANCE_2_0, AdapterCatalog.infer("火山方舟", "video", "doubao-seedance-2-0-260128"));
        assertEquals(AdapterCatalog.SEEDANCE_2_0,
                AdapterCatalog.infer("官方API（token plan）", "video", "doubao-seedance-2.0-fast"));
        assertEquals(AdapterCatalog.SEEDANCE_2, AdapterCatalog.infer("RunningHub", "video", "seedance-2.0"));
        assertTrue(AdapterCatalog.looksLikeOfficialSeedance("doubao-seedance-2.0-fast"));
        assertFalse(AdapterCatalog.looksLikeOfficialSeedance("seedance-2.0"));
        assertEquals(null, AdapterCatalog.infer("官方API", "video", ""));
        assertEquals(null, AdapterCatalog.infer("官方API", "video", "unknown-official-video"));
        assertEquals(AdapterCatalog.GPT_IMAGE_2, AdapterCatalog.infer("RunningHub", "image", "gpt-image-2"));
        assertEquals(AdapterCatalog.GPT_IMAGE_2_0, AdapterCatalog.infer("官方API", "image", "gpt-image-2"));
        assertEquals(AdapterCatalog.GPT_IMAGE_2_5, AdapterCatalog.infer("官方API", "image", "gpt-image-2.5-sunburst"));
        assertEquals(AdapterCatalog.TRANSPORT_OPENAI_IMAGE, AdapterCatalog.transport("gpt-image-2.0", "官方API"));
        assertEquals(AdapterCatalog.GPT_IMAGE_2_0_MODEL, AdapterCatalog.defaultModelKey(AdapterCatalog.GPT_IMAGE_2_0));
        assertEquals(AdapterCatalog.GPT_IMAGE_2_5_MODEL, AdapterCatalog.defaultModelKey(AdapterCatalog.GPT_IMAGE_2_5));
        assertEquals(AdapterCatalog.SEEDREAM_4_0, AdapterCatalog.infer("官方API", "image", "doubao-seedream-4-0-250828"));
        assertEquals(AdapterCatalog.SEEDREAM_4_5, AdapterCatalog.infer("官方API", "image", "doubao-seedream-4-5-251128"));
        assertEquals(AdapterCatalog.SEEDREAM_5_0,
                AdapterCatalog.infer("官方API（token plan）", "image", "doubao-seedream-5.0-lite"));
        assertEquals(AdapterCatalog.SEEDREAM_5_0,
                AdapterCatalog.infer("官方API（token plan）", "image", "doubao-seedream-5.0-pro"));
        assertEquals(AdapterCatalog.TRANSPORT_SEEDREAM, AdapterCatalog.transport("seedream-4.0", "官方API"));
        assertEquals(AdapterCatalog.SEEDREAM_5_0_MODEL, AdapterCatalog.defaultModelKey(AdapterCatalog.SEEDREAM_5_0));
        assertTrue(AdapterCatalog.isSeedream(AdapterCatalog.SEEDREAM_4_5));
        assertEquals(AdapterCatalog.PROVIDER_OFFICIAL_TOKEN_PLAN,
                AdapterCatalog.require(AdapterCatalog.SEEDREAM_5_0).providerLabel());
        assertEquals(AdapterCatalog.PROVIDER_OFFICIAL_TOKEN_PLAN,
                AdapterCatalog.officialProviderOf("官方API（token plan）", AdapterCatalog.SEEDREAM_5_0));
        assertEquals(AdapterCatalog.MOCK, AdapterCatalog.infer("mock", "video", "mock-video"));
        assertEquals(AdapterCatalog.OPENAI_SPEECH, AdapterCatalog.infer("自定义配置", "audio", "gpt-4o-mini-tts"));
        assertEquals(AdapterCatalog.MINIMAX_SPEECH, AdapterCatalog.infer("官方API", "audio", "speech-2.8-hd"));
    }

    @Test
    void pinnedAdapterKeyDoesNotCrossTransport() {
        assertEquals(AdapterCatalog.SEEDANCE_2_0,
                AdapterCatalog.resolveKey("seedance-2.0", "官方API", "video", "agnes-video-2.5-flash"));
        assertEquals(AdapterCatalog.AGNES_VIDEO_2_5,
                AdapterCatalog.resolveKey("agnes-video-2.5", "官方API", "video", "doubao-seedance-2.0-fast"));
        assertEquals(AdapterCatalog.SEEDANCE_2,
                AdapterCatalog.resolveKey("seedance-2", "官方API", "video", "doubao-seedance-2.0-fast"));
        assertEquals(AdapterCatalog.TRANSPORT_ARK, AdapterCatalog.transport("seedance-2.0", "官方API"));
        assertEquals(AdapterCatalog.TRANSPORT_AGNES, AdapterCatalog.transport("agnes-video-2.5", "官方API"));
        assertEquals(AdapterCatalog.TRANSPORT_MINIMAX, AdapterCatalog.transport("minimax-h3", "官方API"));
        assertEquals(AdapterCatalog.TRANSPORT_COMFYUI, AdapterCatalog.transport("minimax-h3", "ComfyUI"));
        assertEquals(AdapterCatalog.TRANSPORT_RUNNINGHUB, AdapterCatalog.transport("seedance-2", "RunningHub"));
        assertThrows(BizException.class,
                () -> AdapterCatalog.resolveKey("not-a-real-adapter", "官方API", "video", "agnes-video-2.5-flash"));
        assertEquals(AdapterCatalog.SEEDANCE_2_0,
                AdapterCatalog.resolveKeyForGenerate("agnes-video-2.5", "官方API", "video", "doubao-seedance-2.0-fast"));
        assertEquals(AdapterCatalog.SEEDANCE_2_0,
                AdapterCatalog.resolveKeyForGenerate(null, "官方API", "video", "doubao-seedance-2.0-fast"));
        assertEquals(AdapterCatalog.SEEDANCE_2,
                AdapterCatalog.resolveKeyForGenerate("seedance-2", "RunningHub", "video", "doubao-seedance-2.0-fast"));
        assertEquals(AdapterCatalog.SEEDANCE_2_0, AdapterCatalog.officialSeedanceAdapter("doubao-seedance-2.0-fast"));
        assertEquals(null, AdapterCatalog.officialSeedanceAdapter("seedance-2.0"));
    }

    @Test
    void seedanceDoesNotSupportI2v() {
        assertTrue(AdapterCatalog.supportsMode(AdapterCatalog.SEEDANCE_2, GenerateMode.T2V));
        assertTrue(AdapterCatalog.supportsMode(AdapterCatalog.SEEDANCE_2, GenerateMode.R2V));
        assertFalse(AdapterCatalog.supportsMode(AdapterCatalog.SEEDANCE_2, GenerateMode.I2V));
        assertTrue(AdapterCatalog.supportsMode(AdapterCatalog.MINIMAX_H3, GenerateMode.I2V));
        assertTrue(AdapterCatalog.supportsMode(AdapterCatalog.MINIMAX_H3, GenerateMode.R2V));
        assertTrue(AdapterCatalog.supportsMode(AdapterCatalog.AGNES_IMAGE_2_5, GenerateMode.T2I));
        assertTrue(AdapterCatalog.supportsMode(AdapterCatalog.AGNES_IMAGE_2_5, GenerateMode.I2I));
        assertTrue(AdapterCatalog.supportsMode(AdapterCatalog.GPT_IMAGE_2, GenerateMode.T2I));
        assertTrue(AdapterCatalog.supportsMode(AdapterCatalog.GPT_IMAGE_2, GenerateMode.I2I));
        assertTrue(AdapterCatalog.supportsMode(AdapterCatalog.GPT_IMAGE_2_0, GenerateMode.T2I));
        assertTrue(AdapterCatalog.supportsMode(AdapterCatalog.GPT_IMAGE_2_5, GenerateMode.I2I));
        assertTrue(AdapterCatalog.supportsMode(AdapterCatalog.SEEDREAM_4_0, GenerateMode.T2I));
        assertTrue(AdapterCatalog.supportsMode(AdapterCatalog.SEEDREAM_5_0, GenerateMode.I2I));
        assertEquals(AdapterCatalog.SEEDREAM_4_0_MODEL, AdapterCatalog.defaultModelKey(AdapterCatalog.SEEDREAM_4_0));
        assertEquals("rhart-image-g-2/image-to-image",
                AdapterCatalog.require(AdapterCatalog.GPT_IMAGE_2).defaultPaths().get("i2iPath"));
        assertTrue(AdapterCatalog.supportsMode(AdapterCatalog.AGNES_VIDEO_2_5, GenerateMode.I2V));
        assertTrue(AdapterCatalog.supportsMode(AdapterCatalog.SEEDANCE_2_5, GenerateMode.T2V));
        assertTrue(AdapterCatalog.supportsMode(AdapterCatalog.SEEDANCE_2_5, GenerateMode.R2V));
        assertTrue(AdapterCatalog.supportsMode(AdapterCatalog.SEEDANCE_2_5, GenerateMode.I2V));
        assertTrue(AdapterCatalog.isAgnes(AdapterCatalog.AGNES_VIDEO_2_5));
        assertTrue(AdapterCatalog.isArk(AdapterCatalog.SEEDANCE_2_5));
        assertTrue(AdapterCatalog.isArk(AdapterCatalog.SEEDANCE_2_0));
        assertTrue(AdapterCatalog.supportsMode(AdapterCatalog.SEEDANCE_2_0, GenerateMode.T2V));
        assertTrue(AdapterCatalog.supportsMode(AdapterCatalog.SEEDANCE_2_0, GenerateMode.R2V));
        assertTrue(AdapterCatalog.supportsMode(AdapterCatalog.SEEDANCE_2_0, GenerateMode.I2V));
        assertEquals(AdapterCatalog.SEEDANCE_2_0_MODEL, AdapterCatalog.defaultModelKey(AdapterCatalog.SEEDANCE_2_0));
        assertEquals("https://api.agnes-ai.cn/v1", AdapterCatalog.require(AdapterCatalog.AGNES_IMAGE_2_5).defaultEndpoint());
        assertEquals("官方API", AdapterCatalog.require(AdapterCatalog.AGNES_VIDEO_2_5).providerLabel());
        assertEquals("官方API", AdapterCatalog.require(AdapterCatalog.SEEDANCE_2_5).providerLabel());
        assertEquals(AdapterCatalog.ARK_DEFAULT_ENDPOINT, AdapterCatalog.require(AdapterCatalog.SEEDANCE_2_5).defaultEndpoint());
        assertEquals(AdapterCatalog.SEEDANCE_2_5_MODEL, AdapterCatalog.defaultModelKey(AdapterCatalog.SEEDANCE_2_5));
        assertEquals(AdapterCatalog.MINIMAX_DEFAULT_ENDPOINT, AdapterCatalog.require(AdapterCatalog.MINIMAX_H3).defaultEndpoint());
        assertEquals(AdapterCatalog.MINIMAX_H3_MODEL, AdapterCatalog.defaultModelKey(AdapterCatalog.MINIMAX_H3));
        assertTrue(AdapterCatalog.isMiniMax(AdapterCatalog.MINIMAX_H3));
        assertEquals("官方API", AdapterCatalog.require(AdapterCatalog.MINIMAX_H3).providerLabel());
        assertTrue(AdapterCatalog.isOfficialTokenPlan("官方API（token plan）"));
        assertFalse(AdapterCatalog.isOfficialTokenPlan("官方API"));
        assertEquals(AdapterCatalog.PROVIDER_OFFICIAL_TOKEN_PLAN,
                AdapterCatalog.officialProviderOf("官方API（token plan）", AdapterCatalog.SEEDANCE_2_0));
        assertEquals(AdapterCatalog.PROVIDER_OFFICIAL,
                AdapterCatalog.officialProviderOf("官方API（token plan）", AdapterCatalog.AGNES_VIDEO_2_5));
        assertEquals(AdapterCatalog.PROVIDER_COMFYUI,
                AdapterCatalog.officialProviderOf("ComfyUI", AdapterCatalog.MINIMAX_H3));
        assertEquals(AdapterCatalog.PROVIDER_OFFICIAL,
                AdapterCatalog.officialProviderOf("官方API", AdapterCatalog.MINIMAX_H3));
        assertTrue(AdapterCatalog.isMiniMaxOfficial(AdapterCatalog.MINIMAX_H3, "官方API"));
        assertFalse(AdapterCatalog.isMiniMaxOfficial(AdapterCatalog.MINIMAX_H3, "ComfyUI"));
        assertTrue(AdapterCatalog.isOpenAiSpeech(AdapterCatalog.OPENAI_SPEECH));
        assertEquals(AdapterCatalog.TRANSPORT_OPENAI_SPEECH,
                AdapterCatalog.transport(AdapterCatalog.OPENAI_SPEECH, "自定义配置"));
        assertTrue(AdapterCatalog.supportsMode(AdapterCatalog.OPENAI_SPEECH, GenerateMode.T2A));
        assertTrue(AdapterCatalog.isMiniMaxSpeech(AdapterCatalog.MINIMAX_SPEECH));
        assertEquals(AdapterCatalog.TRANSPORT_MINIMAX_SPEECH,
                AdapterCatalog.transport(AdapterCatalog.MINIMAX_SPEECH, "官方API"));
    }

    @Test
    void constrainsCapabilitiesToAdapterModes() {
        assertEquals(List.of("video", "t2v", "r2v"),
                AdapterCatalog.constrainCapabilities(
                        List.of("video", "t2v", "r2v", "i2v"),
                        "video",
                        AdapterCatalog.require(AdapterCatalog.SEEDANCE_2)));
    }
}
