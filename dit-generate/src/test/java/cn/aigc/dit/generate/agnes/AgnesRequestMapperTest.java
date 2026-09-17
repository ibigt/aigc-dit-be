package cn.aigc.dit.generate.agnes;

import cn.aigc.dit.generate.AdapterCatalog;
import cn.aigc.dit.generate.GenerateRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgnesRequestMapperTest {

    @Test
    void mapsImageT2iToOfficialBody() {
        GenerateRequest request = new GenerateRequest()
                .setAdapterKey(AdapterCatalog.AGNES_IMAGE_2_5)
                .setModelKey("agnes-image-2.5-flash")
                .setPrompt("发光浮空城市")
                .setResolution("2K")
                .setRatio("16:9");
        Map<String, Object> body = AgnesRequestMapper.toImageBody(request, List.of());
        assertEquals("agnes-image-2.5-flash", body.get("model"));
        assertEquals("2K", body.get("size"));
        assertEquals("16:9", body.get("ratio"));
        @SuppressWarnings("unchecked")
        Map<String, Object> extra = (Map<String, Object>) body.get("extra_body");
        assertEquals("url", extra.get("response_format"));
        assertFalse(extra.containsKey("image"));
    }

    @Test
    void mapsVideoFlashT2vAndClampsSeconds() {
        GenerateRequest request = new GenerateRequest()
                .setAdapterKey(AdapterCatalog.AGNES_VIDEO_2_5)
                .setModelKey("agnes-video-2.5-flash")
                .setMode("t2v")
                .setPrompt("雨后城市")
                .setResolution("4K")
                .setRatio("自动")
                .setDurationSec(15);
        Map<String, Object> body = AgnesRequestMapper.toVideoBody(request, null, null, List.of(), List.of());
        assertEquals("text", body.get("mode"));
        assertEquals("720P", body.get("size"));
        assertEquals("12", body.get("seconds"));
        assertEquals("16:9", body.get("aspect_ratio"));
        assertEquals(1, body.get("n"));
        assertFalse(body.containsKey("images"));
        assertFalse(body.containsKey("videos"));
    }

    @Test
    void prefixesReferencePromptWithPictureTags() {
        GenerateRequest request = new GenerateRequest()
                .setAdapterKey(AdapterCatalog.AGNES_VIDEO_2_5)
                .setModelKey("agnes-video-2.5-flash")
                .setMode("r2v")
                .setPrompt("角色在花田奔跑")
                .setDurationSec(5);
        Map<String, Object> body = AgnesRequestMapper.toVideoBody(
                request, null, null, List.of("data:image/png;base64,xx"), List.of());
        assertEquals("reference", body.get("mode"));
        assertEquals("<Picture 1> 角色在花田奔跑", body.get("prompt"));
        assertEquals(List.of("data:image/png;base64,xx"), body.get("images"));
        assertTrue(AgnesRequestMapper.flashVideo(request));
    }

    @Test
    void mapsI2vToKeyframeMode() {
        GenerateRequest request = new GenerateRequest()
                .setAdapterKey(AdapterCatalog.AGNES_VIDEO_2_5)
                .setModelKey("agnes-video-2.5-flash")
                .setMode("i2v")
                .setPrompt("转身走向窗边")
                .setDurationSec(5);
        Map<String, Object> body = AgnesRequestMapper.toVideoBody(
                request, "https://example.com/first.png", "https://example.com/last.png", List.of(), List.of());
        assertEquals("keyframe", body.get("mode"));
        assertEquals("https://example.com/first.png", body.get("first_frame"));
        assertEquals("https://example.com/last.png", body.get("last_frame"));
    }

    @Test
    void mapsI2vBlankPromptToDefault() {
        GenerateRequest request = new GenerateRequest()
                .setAdapterKey(AdapterCatalog.AGNES_VIDEO_2_5)
                .setModelKey("agnes-video-2.5-flash")
                .setMode("i2v")
                .setPrompt(" ")
                .setDurationSec(5);
        Map<String, Object> body = AgnesRequestMapper.toVideoBody(
                request, "https://example.com/first.png", null, List.of(), List.of());
        assertEquals("keyframe", body.get("mode"));
        assertEquals(cn.aigc.dit.generate.GenerateMode.DEFAULT_I2V_PROMPT, body.get("prompt"));
        assertEquals("https://example.com/first.png", body.get("first_frame"));
        assertFalse(body.containsKey("last_frame"));
    }
}
