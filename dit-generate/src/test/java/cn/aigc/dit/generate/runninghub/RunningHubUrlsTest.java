package cn.aigc.dit.generate.runninghub;

import cn.aigc.dit.generate.GenerateRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunningHubUrlsTest {

    @Test
    void normalizesFrontendApiPathToOpenApiV2() {
        assertEquals("https://www.runninghub.ai/openapi/v2",
                RunningHubUrls.openApiBase("https://www.runninghub.ai/api", "https://www.runninghub.ai"));
        assertEquals("https://www.runninghub.ai/openapi/v2",
                RunningHubUrls.openApiBase("https://www.runninghub.ai", "x"));
        assertEquals("https://www.runninghub.cn/openapi/v2",
                RunningHubUrls.openApiBase("https://www.runninghub.cn/openapi/v2/foo", "x"));
    }

    @Test
    void submitAndQueryUrls() {
        String base = "https://www.runninghub.ai/openapi/v2";
        assertEquals("https://www.runninghub.ai/openapi/v2/rhart-image-g-2/text-to-image",
                RunningHubUrls.submitUrl(base, "rhart-image-g-2/text-to-image"));
        assertEquals("https://www.runninghub.ai/openapi/v2/query", RunningHubUrls.queryUrl(base));
        assertEquals("https://www.runninghub.ai/openapi/v2/media/upload/binary", RunningHubUrls.uploadUrl(base));
        assertTrue(RunningHubUrls.officialChannel("rhart-image-g-2-official/text-to-image"));
        assertFalse(RunningHubUrls.officialChannel("rhart-image-g-2/text-to-image"));
    }

    @Test
    void mapperForces1kAndDefaultAspect() {
        GenerateRequest request = new GenerateRequest()
                .setPrompt("猫")
                .setRatio("16：9")
                .setResolution("4K")
                .setQuality("High")
                .setAdapterConfig(Map.of("t2iPath", "rhart-image-g-2/text-to-image"));
        Map<String, Object> economy = RunningHubRequestMapper.toT2iBody(
                request, "rhart-image-g-2/text-to-image", "1k", "low");
        assertEquals("1k", economy.get("resolution"));
        assertEquals("16:9", economy.get("aspectRatio"));
        assertFalse(economy.containsKey("quality"));

        Map<String, Object> official = RunningHubRequestMapper.toT2iBody(
                request, "rhart-image-g-2-official/text-to-image", "1k", "low");
        assertEquals("low", official.get("quality"));
    }

    @Test
    void mapperTranslatesCanonicalT2vToSeedanceBody() {
        GenerateRequest request = new GenerateRequest()
                .setPrompt("竹林剑客")
                .setRatio("自动")
                .setResolution("720P")
                .setDurationSec(8)
                .setAdapterConfig(Map.of("t2vPath", "rhart-video/sparkvideo-2.0/text-to-video"));
        Map<String, Object> body = RunningHubRequestMapper.toT2vBody(request);
        assertEquals("竹林剑客", body.get("prompt"));
        assertEquals("720p", body.get("resolution"));
        assertEquals("8", body.get("duration"));
        assertEquals("adaptive", body.get("ratio"));
        assertEquals(Boolean.TRUE, body.get("generateAudio"));
        assertEquals(Boolean.FALSE, body.get("webSearch"));
        assertEquals(Boolean.FALSE, body.get("returnLastFrame"));
        assertEquals("rhart-video/sparkvideo-2.0/text-to-video",
                RunningHubRequestMapper.t2vPath(request, "fallback"));
    }

    @Test
    void mapperTranslatesCanonicalR2vWithImageAndVideoUrls() {
        GenerateRequest request = new GenerateRequest()
                .setPrompt("两个人吃火锅")
                .setRatio("16:9")
                .setResolution("720P")
                .setDurationSec(5)
                .setAdapterConfig(Map.of("r2vPath", "rhart-video/sparkvideo-2.0/multimodal-video"));
        Map<String, Object> body = RunningHubRequestMapper.toR2vBody(
                request,
                List.of("https://cdn.example/a.png"),
                List.of("https://cdn.example/b.mp4"));
        assertEquals("@Image 1 @Video 1 两个人吃火锅", body.get("prompt"));
        assertEquals("720p", body.get("resolution"));
        assertEquals("5", body.get("duration"));
        assertEquals(List.of("https://cdn.example/a.png"), body.get("imageUrls"));
        assertEquals(List.of("https://cdn.example/b.mp4"), body.get("videoUrls"));
        assertEquals(Boolean.TRUE, body.get("generateAudio"));
        assertEquals("16:9", body.get("ratio"));
        assertEquals(Boolean.TRUE, body.get("realPersonMode"));
        assertEquals("rhart-video/sparkvideo-2.0/multimodal-video",
                RunningHubRequestMapper.r2vPath(request, "fallback"));
    }
}
