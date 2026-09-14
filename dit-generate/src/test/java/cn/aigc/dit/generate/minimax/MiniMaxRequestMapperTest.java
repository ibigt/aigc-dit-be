package cn.aigc.dit.generate.minimax;

import cn.aigc.dit.generate.AdapterCatalog;
import cn.aigc.dit.generate.GenerateRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MiniMaxRequestMapperTest {

    @Test
    void mapsT2vWithRequiredRatioAnd768p() {
        GenerateRequest request = new GenerateRequest()
                .setAdapterKey(AdapterCatalog.MINIMAX_H3)
                .setModelKey("minimax-h3")
                .setMode("t2v")
                .setPrompt("海边少年打球")
                .setResolution("720P")
                .setRatio("自动")
                .setDurationSec(30);
        Map<String, Object> body = MiniMaxRequestMapper.toCreateBody(request, null, null, List.of(), List.of());
        assertEquals(AdapterCatalog.MINIMAX_H3_MODEL, body.get("model"));
        assertEquals("768P", body.get("resolution"));
        assertEquals("16:9", body.get("ratio"));
        assertEquals(15, body.get("duration"));
        assertEquals(false, body.get("aigc_watermark"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) body.get("content");
        assertEquals("text", content.get(0).get("type"));
        assertEquals("海边少年打球", content.get(0).get("text"));
        assertEquals(1, content.size());
    }

    @Test
    void mapsR2vReferenceImagesAndVideos() {
        GenerateRequest request = new GenerateRequest()
                .setAdapterKey(AdapterCatalog.MINIMAX_H3)
                .setMode("r2v")
                .setPrompt("角色在花田奔跑")
                .setDurationSec(5)
                .setResolution("2K")
                .setRatio("9:16");
        Map<String, Object> body = MiniMaxRequestMapper.toCreateBody(
                request, null, null, List.of("data:image/png;base64,xx"), List.of("https://cdn.example/a.mp4"));
        assertEquals("2K", body.get("resolution"));
        assertEquals("9:16", body.get("ratio"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) body.get("content");
        assertEquals(3, content.size());
        assertEquals("reference_image", content.get(1).get("role"));
        assertEquals("video_url", content.get(2).get("type"));
        assertEquals("reference_video", content.get(2).get("role"));
    }

    @Test
    void mapsI2vAndForcesAdaptiveRatio() {
        GenerateRequest request = new GenerateRequest()
                .setAdapterKey(AdapterCatalog.MINIMAX_H3)
                .setMode("i2v")
                .setPrompt("转身走向窗边")
                .setDurationSec(5)
                .setRatio("16:9")
                .setResolution("1080P");
        Map<String, Object> body = MiniMaxRequestMapper.toCreateBody(
                request, "https://example.com/first.png", "https://example.com/last.png", List.of(), List.of());
        assertEquals("2K", body.get("resolution"));
        assertEquals("adaptive", body.get("ratio"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) body.get("content");
        assertEquals("first_frame", content.get(1).get("role"));
        assertEquals("last_frame", content.get(2).get("role"));
        assertFalse(body.containsKey("images"));
    }

    @Test
    void maxModelClampsDurationAndResolution() {
        GenerateRequest request = new GenerateRequest()
                .setModelKey("MiniMax-H3-Max")
                .setMode("t2v")
                .setPrompt("夜景")
                .setDurationSec(4)
                .setResolution("2K")
                .setRatio("16:9");
        Map<String, Object> body = MiniMaxRequestMapper.toCreateBody(request, null, null, List.of(), List.of());
        assertEquals(AdapterCatalog.MINIMAX_H3_MAX_MODEL, body.get("model"));
        assertEquals(5, body.get("duration"));
        assertEquals("768P", body.get("resolution"));
    }
}
