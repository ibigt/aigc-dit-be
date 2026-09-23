package cn.aigc.dit.generate.openaiimage;

import cn.aigc.dit.common.constant.ModelCategory;
import cn.aigc.dit.generate.AdapterCatalog;
import cn.aigc.dit.generate.GenerateMode;
import cn.aigc.dit.generate.GenerateProperties;
import cn.aigc.dit.generate.GenerateRequest;
import cn.aigc.dit.storage.ObjectStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenAiImageGenerateProviderTest {

    private ObjectStorage storage;
    private OpenAiImageHttp http;
    private OpenAiImageGenerateProvider provider;

    @BeforeEach
    void setUp() {
        storage = mock(ObjectStorage.class);
        http = mock(OpenAiImageHttp.class);
        GenerateProperties properties = new GenerateProperties();
        provider = new OpenAiImageGenerateProvider(storage, properties, http, new ObjectMapper());
    }

    @Test
    void submitT2iStoresPngFromB64() {
        String png = Base64.getEncoder().encodeToString("png-bytes".getBytes(StandardCharsets.UTF_8));
        when(http.postJson(any(), anyString(), anyString()))
                .thenReturn("{\"data\":[{\"b64_json\":\"" + png + "\"}]}");

        GenerateRequest request = new GenerateRequest();
        request.setCategory(ModelCategory.IMAGE);
        request.setMode(GenerateMode.T2I);
        request.setAdapterKey(AdapterCatalog.GPT_IMAGE_2_0);
        request.setModelKey(AdapterCatalog.GPT_IMAGE_2_0_MODEL);
        request.setPrompt("quiet room");
        request.setApiKey("sk-test");
        request.setUserId(7L);
        request.setTaskId(99L);

        var submit = provider.submit(request);
        var poll = provider.poll(submit.getJobId());
        assertTrue(poll.isSucceeded());
        assertEquals("users/7/outputs/99/1.png", poll.getOutputKeys().get(0));

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(http).postJson(eq(URI.create("https://api.openai.com/v1/images/generations")), eq("sk-test"), bodyCaptor.capture());
        assertTrue(bodyCaptor.getValue().contains("\"model\":\"gpt-image-2\""));
        verify(storage).put(eq("users/7/outputs/99/1.png"), any(byte[].class), eq("image/png"));
    }

    @Test
    void submitI2iUsesEditsEndpoint() {
        String png = Base64.getEncoder().encodeToString("edited".getBytes(StandardCharsets.UTF_8));
        when(http.postMultipart(any(), anyString(), any(), anyString()))
                .thenReturn("{\"data\":[{\"b64_json\":\"" + png + "\"}]}");
        when(storage.getBytes("users/7/refs/a.png")).thenReturn("ref".getBytes(StandardCharsets.UTF_8));

        GenerateRequest request = new GenerateRequest();
        request.setCategory(ModelCategory.IMAGE);
        request.setMode(GenerateMode.I2I);
        request.setAdapterKey(AdapterCatalog.GPT_IMAGE_2_5);
        request.setModelKey(AdapterCatalog.GPT_IMAGE_2_5_MODEL);
        request.setPrompt("brighter lights");
        request.setApiKey("sk-test");
        request.setUserId(7L);
        request.setTaskId(100L);
        var ref = new cn.aigc.dit.generate.ReferenceAsset();
        ref.setStorageKey("users/7/refs/a.png");
        ref.setType("image");
        request.setReferences(List.of(ref));

        provider.submit(request);
        verify(http).postMultipart(eq(URI.create("https://api.openai.com/v1/images/edits")), eq("sk-test"), any(), anyString());
    }
}
