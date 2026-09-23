package cn.aigc.dit.server.service;

import cn.aigc.dit.common.security.LoginUser;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.generate.poster.PosterExtractor;
import cn.aigc.dit.server.drama.mapper.DramaAssetMapper;
import cn.aigc.dit.server.entity.Asset;
import cn.aigc.dit.server.mapper.AssetMapper;
import cn.aigc.dit.server.mapper.CreationMapper;
import cn.aigc.dit.server.model.resp.AssetResp;
import cn.aigc.dit.storage.ObjectStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssetServiceUploadMetadataTest {

    @Mock
    private ObjectStorage objectStorage;
    @Mock
    private PosterExtractor posterExtractor;
    @Mock
    private CreationMapper creationMapper;
    @Mock
    private DramaAssetMapper dramaAssetMapper;
    @Mock
    private AssetMapper assetMapper;

    private AssetService service;

    @BeforeEach
    void setUp() {
        service = new AssetService(objectStorage, posterExtractor, creationMapper, dramaAssetMapper);
        ReflectionTestUtils.setField(service, "baseMapper", assetMapper);
        UserContext.set(new LoginUser(7L, "creator", "c@example.com", "创作者", LoginUser.ROLE_CREATOR));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void uploadPersistsImageDimensions() {
        when(assetMapper.insert(any(Asset.class))).thenReturn(1);
        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", pngBytes(640, 480));

        AssetResp resp = service.upload(file, null);

        ArgumentCaptor<Asset> captor = ArgumentCaptor.forClass(Asset.class);
        verify(assetMapper).insert(captor.capture());
        Asset saved = captor.getValue();
        assertEquals(640, saved.getWidth());
        assertEquals(480, saved.getHeight());
        assertNull(saved.getDurationMs());
        assertEquals(640, resp.getWidth());
        assertEquals(480, resp.getHeight());
    }

    @Test
    void uploadKeepsNullsWhenImageProbeFails() {
        when(assetMapper.insert(any(Asset.class))).thenReturn(1);
        MockMultipartFile file = new MockMultipartFile("file", "broken.png", "image/png",
                new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15});

        AssetResp resp = service.upload(file, null);

        ArgumentCaptor<Asset> captor = ArgumentCaptor.forClass(Asset.class);
        verify(assetMapper).insert(captor.capture());
        assertNull(captor.getValue().getWidth());
        assertNull(captor.getValue().getHeight());
        assertNull(resp.getWidth());
        assertNull(resp.getHeight());
        assertNotNull(resp.getUrl());
    }

    private static byte[] pngBytes(int width, int height) {
        try {
            java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(width, height,
                    java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
