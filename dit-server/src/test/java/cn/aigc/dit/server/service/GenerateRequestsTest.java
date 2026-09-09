package cn.aigc.dit.server.service;

import cn.aigc.dit.common.constant.AssetKind;
import cn.aigc.dit.common.security.LoginUser;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.generate.GenerateMode;
import cn.aigc.dit.generate.GenerateRequest;
import cn.aigc.dit.server.entity.Asset;
import cn.aigc.dit.server.generate.GenerateRequests;
import cn.aigc.dit.server.model.req.GenerateSubmitReq;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GenerateRequestsTest {

    @Mock
    private AssetService assetService;

    @BeforeEach
    void setUp() {
        UserContext.set(new LoginUser(1L, "u", "u@test", "U", LoginUser.ROLE_CREATOR));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void buildsCanonicalI2iRequestFromReferences() {
        Asset asset = new Asset();
        asset.setId(9L);
        asset.setKind(AssetKind.IMAGE);
        asset.setStorageKey("users/1/assets/a.png");
        when(assetService.requireOwned(9L)).thenReturn(asset);

        GenerateSubmitReq req = new GenerateSubmitReq();
        req.setModelId(3L);
        req.setPrompt("改成暖棕色绒面");
        req.setCategory("image");
        req.setRatio("16:9");
        req.setResolution("2K");
        req.setQuality("High");
        req.setCopies(1);
        GenerateSubmitReq.ReferenceInput ref = new GenerateSubmitReq.ReferenceInput();
        ref.setAssetId(9L);
        req.setReferences(List.of(ref));

        GenerateRequest request = GenerateRequests.fromSubmit(1L, "mock-image", req, assetService);
        assertEquals(GenerateMode.I2I, request.getMode());
        assertEquals("image", request.getCategory());
        assertEquals("users/1/assets/a.png", request.getReferences().get(0).getStorageKey());
        assertEquals("image", request.getReferences().get(0).getType());
    }

    @Test
    void t2iOnlyCapabilitiesRejectI2i() {
        assertTrue(GenerateRequests.supportsMode(List.of("image", "t2i"), "image", GenerateMode.T2I));
        assertFalse(GenerateRequests.supportsMode(List.of("image", "t2i"), "image", GenerateMode.I2I));
        assertTrue(GenerateRequests.supportsMode(List.of("image", "t2i", "i2i"), "image", GenerateMode.I2I));
        assertTrue(GenerateRequests.supportsMode(List.of("image"), "image", GenerateMode.I2I));
        assertTrue(GenerateRequests.supportsMode(List.of("video", "t2v"), "video", GenerateMode.T2V));
        assertFalse(GenerateRequests.supportsMode(List.of("video", "t2v"), "video", GenerateMode.R2V));
        assertTrue(GenerateRequests.supportsMode(List.of("video", "t2v", "r2v"), "video", GenerateMode.R2V));
        assertTrue(GenerateRequests.supportsMode(List.of("video", "t2v", "i2v"), "video", GenerateMode.I2V));
        assertTrue(GenerateRequests.supportsMode(List.of("video", "frames"), "video", GenerateMode.I2V));
        assertTrue(GenerateRequests.supportsMode(List.of("video", "v2v"), "video", GenerateMode.I2V));
        assertEquals("当前模型不支持参考图，请移除参考素材后再创作",
                GenerateRequests.unsupportedModeMessage(GenerateMode.I2I));
        assertEquals("当前模型不支持参考生视频，请移除参考素材后再创作",
                GenerateRequests.unsupportedModeMessage(GenerateMode.R2V));
    }

    @Test
    void buildsCanonicalR2vRequestFromVideoReference() {
        Asset asset = new Asset();
        asset.setId(11L);
        asset.setKind(AssetKind.VIDEO);
        asset.setStorageKey("users/1/assets/clip.mp4");
        when(assetService.requireOwned(11L)).thenReturn(asset);

        GenerateSubmitReq req = new GenerateSubmitReq();
        req.setModelId(4L);
        req.setPrompt("参考这段视频生成新镜头");
        req.setCategory("video");
        req.setDurationSec(5);
        GenerateSubmitReq.ReferenceInput ref = new GenerateSubmitReq.ReferenceInput();
        ref.setAssetId(11L);
        req.setReferences(List.of(ref));

        GenerateRequest request = GenerateRequests.fromSubmit(1L, "mock-video", req, assetService);
        assertEquals(GenerateMode.R2V, request.getMode());
        assertEquals("video", request.getReferences().get(0).getType());
        assertEquals("users/1/assets/clip.mp4", request.getReferences().get(0).getStorageKey());
    }
}
