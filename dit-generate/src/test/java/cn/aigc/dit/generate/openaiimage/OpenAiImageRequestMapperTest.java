package cn.aigc.dit.generate.openaiimage;

import cn.aigc.dit.generate.AdapterCatalog;
import cn.aigc.dit.generate.GenerateMode;
import cn.aigc.dit.generate.GenerateRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiImageRequestMapperTest {

    @Test
    void defaultsModelByAdapter() {
        GenerateRequest request = new GenerateRequest();
        request.setAdapterKey(AdapterCatalog.GPT_IMAGE_2_0);
        assertEquals(AdapterCatalog.GPT_IMAGE_2_0_MODEL, OpenAiImageRequestMapper.modelName(request));

        request.setAdapterKey(AdapterCatalog.GPT_IMAGE_2_5);
        assertEquals(AdapterCatalog.GPT_IMAGE_2_5_MODEL, OpenAiImageRequestMapper.modelName(request));

        request.setModelKey("gpt-image-2.5-flare");
        assertEquals("gpt-image-2.5-flare", OpenAiImageRequestMapper.modelName(request));
    }

    @Test
    void generateBodySharesFields() {
        GenerateRequest request = new GenerateRequest();
        request.setAdapterKey(AdapterCatalog.GPT_IMAGE_2_0);
        request.setPrompt("a quiet room");
        request.setRatio("16:9");
        request.setResolution("1K");
        request.setMode(GenerateMode.T2I);

        var body = OpenAiImageRequestMapper.toGenerateBody(request);
        assertEquals("gpt-image-2", body.get("model"));
        assertEquals("a quiet room", body.get("prompt"));
        assertEquals("1536x1024", body.get("size"));
        assertEquals("medium", body.get("quality"));
        assertEquals(1, body.get("n"));
        assertTrue(OpenAiImageRequestMapper.toEditFields(request).containsKey("model"));
    }
}
