package cn.aigc.dit.generate.seedream;

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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SeedreamGenerateProviderTest {

    private ObjectStorage storage;
    private SeedreamHttp http;
    private SeedreamGenerateProvider provider;

    @BeforeEach
    void setUp() {
        storage = mock(ObjectStorage.class);
        http = mock(SeedreamHttp.class);
        GenerateProperties properties = new GenerateProperties();
        provider = new SeedreamGenerateProvider(storage, properties, http, new ObjectMapper());
    }

    @Test
    void submitT2iDownloadsUrlAndStoresPng() {
        when(http.postJson(any(), anyString(), anyString()))
                .thenReturn("{\"data\":[{\"url\":\"https://cdn.example/out.png\"}]}");
        when(http.getBytes(URI.create("https://cdn.example/out.png")))
                .thenReturn("png-bytes".getBytes(StandardCharsets.UTF_8));

        GenerateRequest request = new GenerateRequest();
        request.setCategory(ModelCategory.IMAGE);
        request.setMode(GenerateMode.T2I);
        request.setAdapterKey(AdapterCatalog.SEEDREAM_4_0);
        request.setModelKey(AdapterCatalog.SEEDREAM_4_0_MODEL);
        request.setPrompt("quiet room");
        request.setApiKey("ark-test");
        request.setEndpoint("https://ark.cn-beijing.volces.com/api/v3");
        request.setUserId(7L);
        request.setTaskId(99L);

        var submit = provider.submit(request);
        var poll = provider.poll(submit.getJobId());
        assertTrue(poll.isSucceeded());
        assertEquals("users/7/outputs/99/1.png", poll.getOutputKeys().get(0));

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(http).postJson(
                eq(URI.create("https://ark.cn-beijing.volces.com/api/v3/images/generations")),
                eq("ark-test"),
                bodyCaptor.capture());
        assertTrue(bodyCaptor.getValue().contains("\"model\":\"doubao-seedream-4-0-250828\""));
        assertTrue(bodyCaptor.getValue().contains("\"watermark\":false"));
        verify(storage).put(eq("users/7/outputs/99/1.png"), any(byte[].class), eq("image/png"));
        verify(storage, never()).put(eq("users/7/outputs/99/1.png.origin.json"), any(byte[].class), anyString());
    }

    @Test
    void submitLiteT2iKeepsOriginHttps() {
        when(http.postJson(any(), anyString(), anyString()))
                .thenReturn("{\"data\":[{\"url\":\"https://cdn.example/lite.png?X-Tos-Date=20261009T030000Z&X-Tos-Expires=86400\"}]}");
        when(http.getBytes(any())).thenReturn("png-bytes".getBytes(StandardCharsets.UTF_8));

        GenerateRequest request = new GenerateRequest();
        request.setCategory(ModelCategory.IMAGE);
        request.setMode(GenerateMode.T2I);
        request.setAdapterKey(AdapterCatalog.SEEDREAM_5_0);
        request.setModelKey("doubao-seedream-5.0-lite");
        request.setPrompt("character sheet");
        request.setApiKey("plan-key");
        request.setEndpoint("https://ark.cn-beijing.volces.com/api/plan/v3");
        request.setUserId(7L);
        request.setTaskId(101L);

        provider.submit(request);

        ArgumentCaptor<byte[]> bodyCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(storage).put(eq("users/7/outputs/101/1.png.origin.json"), bodyCaptor.capture(), eq("application/json"));
        String json = new String(bodyCaptor.getValue(), StandardCharsets.UTF_8);
        assertTrue(json.contains("https://cdn.example/lite.png"));
        assertTrue(json.contains("savedAt"));
    }

    @Test
    void submitI2iUsesPlanBaseAndDataUri() {
        when(http.postJson(any(), anyString(), anyString()))
                .thenReturn("{\"data\":[{\"url\":\"https://cdn.example/edit.png\"}]}");
        when(http.getBytes(any())).thenReturn("edited".getBytes(StandardCharsets.UTF_8));
        when(storage.getBytes("users/7/refs/a.png")).thenReturn("ref".getBytes(StandardCharsets.UTF_8));

        GenerateRequest request = new GenerateRequest();
        request.setCategory(ModelCategory.IMAGE);
        request.setMode(GenerateMode.I2I);
        request.setAdapterKey(AdapterCatalog.SEEDREAM_5_0);
        request.setModelKey("doubao-seedream-5.0-pro");
        request.setPrompt("brighter lights");
        request.setApiKey("plan-key");
        request.setEndpoint("https://ark.cn-beijing.volces.com/api/plan/v3");
        request.setUserId(7L);
        request.setTaskId(100L);
        var ref = new cn.aigc.dit.generate.ReferenceAsset();
        ref.setStorageKey("users/7/refs/a.png");
        ref.setType("image");
        request.setReferences(List.of(ref));

        provider.submit(request);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(http).postJson(
                eq(URI.create("https://ark.cn-beijing.volces.com/api/plan/v3/images/generations")),
                eq("plan-key"),
                bodyCaptor.capture());
        assertTrue(bodyCaptor.getValue().contains("\"model\":\"doubao-seedream-5.0-pro\""));
        assertTrue(bodyCaptor.getValue().contains("data:image/png;base64,"));
        verify(storage, never()).put(eq("users/7/outputs/100/1.png.origin.json"), any(byte[].class), anyString());
    }

    @Test
    void submitProT2iKeepsOriginHttps() {
        when(http.postJson(any(), anyString(), anyString()))
                .thenReturn("{\"data\":[{\"url\":\"https://cdn.example/pro.png\"}]}");
        when(http.getBytes(any())).thenReturn("png-bytes".getBytes(StandardCharsets.UTF_8));

        GenerateRequest request = new GenerateRequest();
        request.setCategory(ModelCategory.IMAGE);
        request.setMode(GenerateMode.T2I);
        request.setAdapterKey(AdapterCatalog.SEEDREAM_5_0);
        request.setModelKey("doubao-seedream-5-0-pro");
        request.setPrompt("character sheet");
        request.setApiKey("plan-key");
        request.setEndpoint("https://ark.cn-beijing.volces.com/api/plan/v3");
        request.setUserId(7L);
        request.setTaskId(102L);

        provider.submit(request);

        ArgumentCaptor<byte[]> bodyCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(storage).put(eq("users/7/outputs/102/1.png.origin.json"), bodyCaptor.capture(), eq("application/json"));
        assertTrue(new String(bodyCaptor.getValue(), StandardCharsets.UTF_8).contains("https://cdn.example/pro.png"));
    }

    @Test
    void writeTextToImageStoresBesideTheGivenKey() {
        when(http.postJson(any(), anyString(), anyString()))
                .thenReturn("{\"data\":[{\"url\":\"https://cdn.example/sheet.png\"}]}");
        when(http.getBytes(any())).thenReturn("png-bytes".getBytes(StandardCharsets.UTF_8));

        GenerateRequest request = new GenerateRequest();
        request.setCategory(ModelCategory.IMAGE);
        request.setMode(GenerateMode.T2I);
        request.setAdapterKey(AdapterCatalog.SEEDREAM_5_0);
        request.setModelKey(AdapterCatalog.SEEDREAM_5_0_MODEL);
        request.setPrompt("定妆拼版");
        request.setApiKey("plan-key");
        request.setEndpoint("https://ark.cn-beijing.volces.com/api/plan/v3");

        String key = provider.writeTextToImage(request, "users/7/outputs/249/1.png.seedream.png");

        assertEquals("users/7/outputs/249/1.png.seedream.png", key);
        verify(storage).put(eq("users/7/outputs/249/1.png.seedream.png"), any(byte[].class), eq("image/png"));
        verify(storage).put(eq("users/7/outputs/249/1.png.seedream.png.origin.json"), any(byte[].class), eq("application/json"));
        verify(storage, never()).put(eq("users/0/outputs/0/1.png"), any(byte[].class), anyString());
        assertTrue(!provider.poll("not-a-job").isSucceeded());
    }
}
