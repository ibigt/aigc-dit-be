package cn.aigc.dit.generate.dashscope;

import cn.aigc.dit.generate.AdapterCatalog;
import cn.aigc.dit.generate.GenerateRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DashScopeRequestMapperTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void mapsT2vBody() throws Exception {
        GenerateRequest request = video("t2v");
        JsonNode body = body(request, List.of());
        assertEquals("wan3.0-video", body.path("model").asText());
        assertEquals("雨后的未来城市街道", body.path("input").path("prompt").asText());
        assertEquals("720P", body.path("parameters").path("resolution").asText());
        assertEquals("16:9", body.path("parameters").path("ratio").asText());
        assertEquals(5, body.path("parameters").path("duration").asInt());
        assertFalse(body.path("input").has("media"));
        assertFalse(body.path("parameters").has("prompt_extend"));
        assertFalse(body.path("parameters").has("audio"));
    }

    @Test
    void mapsFirstAndLastFrameAsMedia() throws Exception {
        GenerateRequest request = video("i2v");
        JsonNode body = body(request, List.of(
                new DashScopeRequestMapper.MediaItem("first_frame", "https://storage/first.png"),
                new DashScopeRequestMapper.MediaItem("last_frame", "https://storage/last.png")));
        assertEquals(2, body.path("input").path("media").size());
        assertEquals("first_frame", body.path("input").path("media").get(0).path("type").asText());
        assertEquals("https://storage/first.png", body.path("input").path("media").get(0).path("url").asText());
        assertEquals("last_frame", body.path("input").path("media").get(1).path("type").asText());
        assertEquals("https://storage/last.png", body.path("input").path("media").get(1).path("url").asText());
        assertEquals("根据首帧画面自然生成视频", body.path("input").path("prompt").asText());
    }

    @Test
    void mapsReferenceImagesVideosAndAudios() throws Exception {
        GenerateRequest request = video("r2v")
                .setPrompt("音频1说话，参考图1中的角色走进侦探社");
        JsonNode body = body(request, List.of(
                new DashScopeRequestMapper.MediaItem("reference_image", "https://storage/a.png"),
                new DashScopeRequestMapper.MediaItem("reference_video", "https://storage/b.mp4"),
                new DashScopeRequestMapper.MediaItem("reference_audio", "https://storage/v1.mp3"),
                new DashScopeRequestMapper.MediaItem("reference_audio", "https://storage/v2.wav")));

        JsonNode media = body.path("input").path("media");
        assertEquals(4, media.size());
        assertEquals("reference_image", media.get(0).path("type").asText());
        assertEquals("reference_video", media.get(1).path("type").asText());
        assertEquals("reference_audio", media.get(2).path("type").asText());
        assertEquals("https://storage/v1.mp3", media.get(2).path("url").asText());
        assertEquals("reference_audio", media.get(3).path("type").asText());
        assertFalse(body.path("input").has("reference_urls"));
        assertFalse(body.path("input").has("audio_url"));
    }

    @Test
    void overrideConfigWins() throws Exception {
        GenerateRequest request = video("t2v");
        request.setAdapterConfig(Map.of(
                "resolution", "1080P",
                "duration", 10,
                "generateAudio", false,
                "seed", "42",
                "negativePrompt", "低清"));
        JsonNode body = body(request, List.of());
        assertEquals("1080P", body.path("parameters").path("resolution").asText());
        assertEquals(10, body.path("parameters").path("duration").asInt());
        assertEquals(false, body.path("parameters").path("audio").asBoolean());
        assertEquals(42, body.path("parameters").path("seed").asLong());
        assertEquals("低清", body.path("input").path("negative_prompt").asText());
    }

    private JsonNode body(GenerateRequest request, List<DashScopeRequestMapper.MediaItem> media)
            throws Exception {
        return mapper.readTree(mapper.writeValueAsString(
                DashScopeRequestMapper.toCreateBody(request, media)));
    }

    private static GenerateRequest video(String mode) {
        return new GenerateRequest()
                .setUserId(8L)
                .setTaskId(42L)
                .setCategory("video")
                .setAdapterKey(AdapterCatalog.WAN3)
                .setModelKey("wan3.0-video")
                .setMode(mode)
                .setPrompt(mode.equals("t2v") ? "雨后的未来城市街道" : "")
                .setResolution("720P")
                .setRatio("16:9")
                .setDurationSec(5)
                .setEndpoint("https://dashscope.aliyuncs.com/api/v1")
                .setApiKey("sk-dashscope");
    }
}
