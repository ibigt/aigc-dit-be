package cn.aigc.dit.server.service;

import cn.aigc.dit.common.constant.ModelCategory;
import cn.aigc.dit.server.crypto.AesGcmEncryptor;
import cn.aigc.dit.server.entity.AiModel;
import cn.aigc.dit.server.mapper.AiModelMapper;
import cn.aigc.dit.server.model.req.AdminModelSaveReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminModelServiceTest {

    @Mock
    private AiModelMapper modelMapper;
    @Mock
    private AesGcmEncryptor encryptor;

    private AdminModelService service;

    @BeforeEach
    void setUp() {
        service = new AdminModelService(encryptor);
        ReflectionTestUtils.setField(service, "baseMapper", modelMapper);
    }

    @Test
    void createEncryptsApiKeyAndHidesItInResponse() {
        when(modelMapper.selectCount(any())).thenReturn(0L);
        when(encryptor.encrypt("sk-test")).thenReturn("v1:cipher");
        when(modelMapper.insert(any(AiModel.class))).thenAnswer(invocation -> {
            AiModel model = invocation.getArgument(0);
            model.setId(3L);
            assertEquals("v1:cipher", model.getApiKeyEnc());
            return 1;
        });

        AdminModelSaveReq req = new AdminModelSaveReq();
        req.setName("Seedream 5.0");
        req.setModelKey("seedream-5");
        req.setProvider("RunningHub");
        req.setCategory(ModelCategory.IMAGE);
        req.setApiKey("sk-test");
        req.setPricing(Map.of("unit", "张"));
        req.setCapabilities(List.of("image", "t2i"));
        req.setAdapterConfig(Map.of("t2iPath", "rhart-image-g-2/text-to-image"));

        var resp = service.create(req);
        assertEquals(3L, resp.getId());
        assertTrue(resp.isHasApiKey());
        assertEquals("图像", resp.getBadge());
        assertEquals(List.of("image", "t2i"), resp.getCapabilities());
        assertEquals("rhart-image-g-2/text-to-image", resp.getAdapterConfig().get("t2iPath"));

    }

    @Test
    void updateKeepsExistingKeyWhenBlank() {
        AiModel existing = new AiModel();
        existing.setId(3L);
        existing.setApiKeyEnc("v1:old");
        existing.setModelKey("seedream-5");
        existing.setCategory(ModelCategory.IMAGE);
        when(modelMapper.selectById(3L)).thenReturn(existing);
        when(modelMapper.selectCount(any())).thenReturn(0L);
        when(modelMapper.updateById(any(AiModel.class))).thenReturn(1);

        AdminModelSaveReq req = new AdminModelSaveReq();
        req.setName("Seedream 5.0");
        req.setModelKey("seedream-5");
        req.setProvider("RunningHub");
        req.setCategory(ModelCategory.IMAGE);
        req.setApiKey("  ");
        req.setEnabled(false);

        var resp = service.update(3L, req);
        assertTrue(resp.isHasApiKey());
        assertFalse(resp.getEnabled());
        assertEquals("v1:old", existing.getApiKeyEnc());
    }

    @Test
    void publicListDropsSecrets() {
        AiModel model = new AiModel();
        model.setId(1L);
        model.setName("Mock");
        model.setModelKey("mock-1");
        model.setProvider("mock");
        model.setCategory(ModelCategory.VIDEO);
        model.setEnabled(true);
        model.setApiKeyEnc("v1:secret");
        model.setEndpoint("http://127.0.0.1:8188");
        model.setCapabilities(List.of("video", "t2v", "i2v", "frames"));

        var pub = service.toPublicResp(model);
        assertEquals("Mock", pub.getName());
        assertEquals(Boolean.TRUE, pub.getEnabled());
        assertEquals(List.of("video", "t2v", "r2v", "v2v"), pub.getCapabilities());
    }

    @Test
    void normalizeCapabilitiesRewritesLegacyVideoModes() {
        assertEquals(List.of("video", "t2v", "r2v", "v2v"),
                AdminModelService.normalizeCapabilities(List.of("video", "t2v", "i2v", "frames"), "video"));
    }
}
