package cn.aigc.dit.server.service;

import cn.aigc.dit.common.constant.AssetKind;
import cn.aigc.dit.common.security.LoginUser;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.generate.GenerateMode;
import cn.aigc.dit.generate.GenerateRequest;
import cn.aigc.dit.server.entity.Asset;
import cn.aigc.dit.server.entity.GenTask;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
        assertFalse(GenerateRequests.isDramaShot(java.util.Map.of(
                "source", GenerateRequests.SOURCE_DRAMA,
                "shotId", 201L,
                "openingStill", true)));
        assertTrue(GenerateRequests.isOpeningStill(java.util.Map.of(
                "source", GenerateRequests.SOURCE_DRAMA,
                "shotId", 201L,
                "openingStill", true)));
        assertTrue(GenerateRequests.continueStillToI2v(java.util.Map.of(
                "source", GenerateRequests.SOURCE_DRAMA,
                "shotId", 201L,
                "openingStill", true,
                "stillForI2v", true)));
        assertEquals("当前模型不支持参考图，请移除参考素材后再创作",
                GenerateRequests.unsupportedModeMessage(GenerateMode.I2I));
        assertEquals("当前模型不支持参考生视频，请移除参考素材后再创作",
                GenerateRequests.unsupportedModeMessage(GenerateMode.R2V));
    }

    @Test
    void storageKeyReferenceDoesNotRequireLibraryRow() {
        GenerateSubmitReq req = new GenerateSubmitReq();
        req.setModelId(4L);
        req.setPrompt("参考图出片");
        req.setCategory("video");
        req.setDurationSec(5);
        GenerateSubmitReq.ReferenceInput ref = new GenerateSubmitReq.ReferenceInput();
        ref.setStorageKey("users/1/gen/char.png");
        ref.setType("image");
        req.setReferences(List.of(ref));

        GenerateRequest request = GenerateRequests.fromSubmit(1L, "mock-video", req, assetService);
        assertEquals(GenerateMode.R2V, request.getMode());
        assertEquals("users/1/gen/char.png", request.getReferences().get(0).getStorageKey());
        verify(assetService).assertOwnedStorageKey("users/1/gen/char.png");
        verify(assetService, never()).requireOwnedKey(any());
        verify(assetService, never()).requireOwned(any());
    }

    @Test
    void rejectsMoreThanFifteenReferencesBeforeResolvingAssets() {
        GenerateSubmitReq req = new GenerateSubmitReq();
        req.setCategory("video");
        GenerateSubmitReq.ReferenceInput ref = new GenerateSubmitReq.ReferenceInput();
        ref.setAssetId(9L);
        req.setReferences(java.util.Collections.nCopies(16, ref));

        cn.aigc.dit.common.exception.BizException error = assertThrows(
                cn.aigc.dit.common.exception.BizException.class,
                () -> GenerateRequests.fromSubmit(1L, "mock-video", req, assetService));

        assertEquals("参考素材最多选择15个", error.getMessage());
        verify(assetService, never()).requireOwned(any());
    }

    @Test
    void dramaSourceDoesNotBindQuickCreate() {
        assertTrue(GenerateRequests.bindsQuickCreate(null));
        assertTrue(GenerateRequests.bindsQuickCreate(java.util.Map.of("source", GenerateRequests.SOURCE)));
        assertFalse(GenerateRequests.bindsQuickCreate(java.util.Map.of("source", GenerateRequests.SOURCE_DRAMA)));
        assertTrue(GenerateRequests.isDramaSource(java.util.Map.of("source", GenerateRequests.SOURCE_DRAMA)));
        assertTrue(GenerateRequests.isDramaShot(java.util.Map.of(
                "source", GenerateRequests.SOURCE_DRAMA, "shotId", 201L)));
        assertFalse(GenerateRequests.isDramaShot(java.util.Map.of("source", GenerateRequests.SOURCE_DRAMA)));
        assertEquals(31L, GenerateRequests.sessionIdFromParams(java.util.Map.of("sessionId", 31)));
        assertEquals(null, GenerateRequests.sessionIdFromParams(java.util.Map.of("source", GenerateRequests.SOURCE_DRAMA)));
        assertEquals(201L, GenerateRequests.shotIdFromParams(java.util.Map.of("shotId", 201)));
        assertEquals(201L, GenerateRequests.shotIdFromParams(java.util.Map.of("shotId", "201")));
        assertEquals(18L, GenerateRequests.seriesIdFromParams(java.util.Map.of("seriesId", 18)));
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

    @Test
    void preservesAudioReferenceKind() {
        Asset asset = new Asset();
        asset.setId(12L);
        asset.setKind(AssetKind.AUDIO);
        asset.setStorageKey("users/1/assets/voice.wav");
        when(assetService.requireOwned(12L)).thenReturn(asset);
        GenerateSubmitReq req = new GenerateSubmitReq();
        req.setPrompt("参考角色音色生成有声视频");
        req.setCategory("video");
        GenerateSubmitReq.ReferenceInput ref = new GenerateSubmitReq.ReferenceInput();
        ref.setAssetId(12L);
        req.setReferences(List.of(ref));
        GenerateRequest request = GenerateRequests.fromSubmit(1L, "minimax-h3", req, assetService);
        assertEquals(AssetKind.AUDIO, request.getReferences().get(0).getType());
    }

    @Test
    void firstFrameOnlyDoesNotRequireLibraryRow() {
        GenerateSubmitReq req = new GenerateSubmitReq();
        req.setModelId(4L);
        req.setPrompt("从首帧起幅");
        req.setCategory("video");
        req.setDurationSec(5);
        GenerateSubmitReq.FrameInput frames = new GenerateSubmitReq.FrameInput();
        frames.setFirstFrameKey("users/1/drama/21/frames/a.webp");
        req.setFrames(frames);

        GenerateRequest request = GenerateRequests.fromSubmit(1L, "mock-video", req, assetService);
        assertEquals(GenerateMode.I2V, request.getMode());
        assertEquals("users/1/drama/21/frames/a.webp", request.getFrames().getFirstFrameKey());
        assertNull(request.getFrames().getLastFrameKey());
        verify(assetService).assertOwnedStorageKey("users/1/drama/21/frames/a.webp");
        verify(assetService, never()).requireOwnedKey(any());
    }

    @Test
    void persistsAdapterKeyOnTaskParams() {
        GenerateRequest request = new GenerateRequest()
                .setMode(GenerateMode.T2V)
                .setRatio("16:9")
                .setAdapterKey("seedance-2.0");
        java.util.Map<String, Object> params = GenerateRequests.toParams(request, null);
        assertEquals("seedance-2.0", params.get("adapterKey"));
        GenTask task = new GenTask();
        task.setCategory("video");
        task.setParams(params);
        GenerateRequest restored = GenerateRequests.fromTask(task, "doubao-seedance-2.0-fast");
        assertEquals("seedance-2.0", restored.getAdapterKey());
    }
}
