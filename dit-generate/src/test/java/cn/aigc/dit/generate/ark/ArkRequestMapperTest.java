package cn.aigc.dit.generate.ark;

import cn.aigc.dit.generate.AdapterCatalog;
import cn.aigc.dit.generate.GenerateRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArkRequestMapperTest {

    @Test
    void mapsT2vAndClampsOfficialFields() {
        GenerateRequest request = new GenerateRequest()
                .setAdapterKey(AdapterCatalog.SEEDANCE_2_5)
                .setModelKey("doubao-seedance-2-5-260628")
                .setMode("t2v")
                .setPrompt("雨后城市")
                .setResolution("1080P")
                .setRatio("自动")
                .setDurationSec(30);
        Map<String, Object> body = ArkRequestMapper.toCreateBody(request, null, null, List.of());
        assertEquals("doubao-seedance-2-5-260628", body.get("model"));
        assertEquals("720p", body.get("resolution"));
        assertEquals("adaptive", body.get("ratio"));
        assertEquals(15, body.get("duration"));
        assertEquals(true, body.get("generate_audio"));
        assertEquals(false, body.get("watermark"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) body.get("content");
        assertEquals("text", content.get(0).get("type"));
        assertEquals("雨后城市", content.get(0).get("text"));
        assertEquals(1, content.size());
    }

    @Test
    void respectsConfiguredAudioGenerationSwitch() {
        GenerateRequest request = new GenerateRequest()
                .setAdapterKey(AdapterCatalog.SEEDANCE_2_5)
                .setMode("t2v")
                .setPrompt("安静的雪山")
                .setAdapterConfig(Map.of("generateAudio", false));

        Map<String, Object> body = ArkRequestMapper.toCreateBody(request, null, null, List.of());

        assertEquals(false, body.get("generate_audio"));
    }

    @Test
    void mapsR2vReferenceImages() {
        GenerateRequest request = new GenerateRequest()
                .setAdapterKey(AdapterCatalog.SEEDANCE_2_5)
                .setMode("r2v")
                .setPrompt("角色在花田奔跑")
                .setDurationSec(5)
                .setResolution("480P")
                .setRatio("9:16");
        Map<String, Object> body = ArkRequestMapper.toCreateBody(
                request, null, null, List.of("data:image/png;base64,xx"));
        assertEquals("480p", body.get("resolution"));
        assertEquals("9:16", body.get("ratio"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) body.get("content");
        assertEquals(2, content.size());
        assertEquals("image_url", content.get(1).get("type"));
        assertEquals("reference_image", content.get(1).get("role"));
        @SuppressWarnings("unchecked")
        Map<String, Object> imageUrl = (Map<String, Object>) content.get(1).get("image_url");
        assertEquals("data:image/png;base64,xx", imageUrl.get("url"));
    }

    @Test
    void mapsI2vFirstAndLastFrame() {
        GenerateRequest request = new GenerateRequest()
                .setAdapterKey(AdapterCatalog.SEEDANCE_2_5)
                .setMode("i2v")
                .setPrompt("转身走向窗边")
                .setDurationSec(5);
        Map<String, Object> body = ArkRequestMapper.toCreateBody(
                request, "https://example.com/first.png", "https://example.com/last.png", List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) body.get("content");
        assertEquals(3, content.size());
        assertEquals("first_frame", content.get(1).get("role"));
        assertEquals("last_frame", content.get(2).get("role"));
        assertFalse(body.containsKey("images"));
    }

    @Test
    void mapsI2vFirstFrameOnly() {
        GenerateRequest request = new GenerateRequest()
                .setAdapterKey(AdapterCatalog.SEEDANCE_2_5)
                .setMode("i2v")
                .setPrompt("局长推门进来")
                .setDurationSec(5);
        Map<String, Object> body = ArkRequestMapper.toCreateBody(
                request, "https://example.com/first.png", null, List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) body.get("content");
        assertEquals(2, content.size());
        assertEquals("first_frame", content.get(1).get("role"));
    }

    @Test
    void mapsI2vFirstFrameWithoutPrompt() {
        GenerateRequest request = new GenerateRequest()
                .setAdapterKey(AdapterCatalog.SEEDANCE_2_5)
                .setMode("i2v")
                .setPrompt("  ")
                .setDurationSec(5);
        Map<String, Object> body = ArkRequestMapper.toCreateBody(
                request, "https://example.com/first.png", null, List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) body.get("content");
        assertEquals(1, content.size());
        assertEquals("first_frame", content.get(0).get("role"));
    }

    @Test
    void mapsOfficialMultimodalRefsAndKeeps1080ForSeedance20() {
        GenerateRequest request = new GenerateRequest()
                .setAdapterKey(AdapterCatalog.SEEDANCE_2_0)
                .setModelKey("doubao-seedance-2-0-260128")
                .setMode("r2v")
                .setPrompt("全程使用视频1的第一视角构图")
                .setResolution("1080P")
                .setRatio("16:9")
                .setDurationSec(11);
        Map<String, Object> body = ArkRequestMapper.toCreateBody(
                request,
                null,
                null,
                List.of("https://example.com/pic1.jpg"),
                List.of("https://example.com/video1.mp4"));
        assertEquals("1080p", body.get("resolution"));
        assertEquals(11, body.get("duration"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) body.get("content");
        assertEquals("reference_image", content.get(1).get("role"));
        assertEquals("video_url", content.get(2).get("type"));
        assertEquals("reference_video", content.get(2).get("role"));
        assertEquals(3, content.size());
    }

    @Test
    void mapsBase64AudioReferenceAndAllowsOmittedText() {
        GenerateRequest request = new GenerateRequest()
                .setAdapterKey(AdapterCatalog.SEEDANCE_2_5)
                .setModelKey("doubao-seedance-2-5-260628")
                .setCategory("video")
                .setMode("r2v")
                .setPrompt("");
        Map<String, Object> body = ArkRequestMapper.toCreateBody(
                request, null, null, List.of(), List.of(),
                List.of("data:audio/wav;base64,AQID"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) body.get("content");
        assertEquals(1, content.size());
        assertEquals("audio_url", content.get(0).get("type"));
        assertEquals("reference_audio", content.get(0).get("role"));
        @SuppressWarnings("unchecked")
        Map<String, Object> audioUrl = (Map<String, Object>) content.get(0).get("audio_url");
        assertEquals("data:audio/wav;base64,AQID", audioUrl.get("url"));
        assertEquals(10, ArkRequestMapper.maxAudios(request));
        assertTrue(ArkRequestMapper.supportsAudioOnly(request));
    }

    @Test
    void fastModelClamps1080DownTo720() {
        GenerateRequest request = new GenerateRequest()
                .setAdapterKey(AdapterCatalog.SEEDANCE_2_0)
                .setModelKey("doubao-seedance-2-0-fast-260128")
                .setResolution("1080P");
        assertEquals("720p", ArkRequestMapper.clampResolution(request));
    }

    @Test
    void miniModelClamps1080DownTo720() {
        // 官方 doubao-seedance-2.0-mini 仅支持 480p / 720p，与 fast 同档
        GenerateRequest request = new GenerateRequest()
                .setAdapterKey(AdapterCatalog.SEEDANCE_2_0)
                .setModelKey("doubao-seedance-2.0-mini")
                .setResolution("1080P");
        assertEquals("720p", ArkRequestMapper.clampResolution(request));
        assertEquals(List.of("480P", "720P"),
                ArkRequestMapper.supportedResolutions(AdapterCatalog.SEEDANCE_2_0, "doubao-seedance-2.0-mini"));
    }

    @Test
    void standardModelKeeps1080p() {
        GenerateRequest request = new GenerateRequest()
                .setAdapterKey(AdapterCatalog.SEEDANCE_2_0)
                .setModelKey("seedance-2-0-202602")
                .setResolution("1080P");
        assertEquals("1080p", ArkRequestMapper.clampResolution(request));
    }
}
