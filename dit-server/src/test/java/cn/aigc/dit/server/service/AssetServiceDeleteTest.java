package cn.aigc.dit.server.service;

import cn.aigc.dit.common.security.LoginUser;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.generate.poster.PosterExtractor;
import cn.aigc.dit.server.drama.mapper.DramaAssetMapper;
import cn.aigc.dit.server.entity.Asset;
import cn.aigc.dit.server.mapper.AssetMapper;
import cn.aigc.dit.server.mapper.CreationMapper;
import cn.aigc.dit.storage.ObjectStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssetServiceDeleteTest {

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
    void preservesObjectWhenDramaReferencesImageOrVoice() {
        Asset asset = asset(12L, "users/7/assets/reference.png", "users/7/assets/poster.jpg");
        when(assetMapper.selectById(12L)).thenReturn(asset);
        when(creationMapper.selectCount(any())).thenReturn(0L);
        when(dramaAssetMapper.selectCount(any())).thenReturn(1L);

        service.deleteMine(12L);

        verify(dramaAssetMapper).selectCount(any());
        verify(objectStorage, never()).delete(any());
        verify(assetMapper).deleteById(12L);
    }

    @Test
    void deletesObjectAndPosterWhenAssetIsUnreferenced() {
        Asset asset = asset(13L, "users/7/assets/source.mp4", "users/7/assets/poster.jpg");
        when(assetMapper.selectById(13L)).thenReturn(asset);
        when(creationMapper.selectCount(any())).thenReturn(0L);
        when(dramaAssetMapper.selectCount(any())).thenReturn(0L);

        service.deleteMine(13L);

        verify(objectStorage).delete("users/7/assets/source.mp4");
        verify(objectStorage).delete("users/7/assets/poster.jpg");
        verify(assetMapper).deleteById(13L);
    }

    private static Asset asset(Long id, String storageKey, String posterKey) {
        Asset asset = new Asset();
        asset.setId(id);
        asset.setUserId(7L);
        asset.setStorageKey(storageKey);
        asset.setPosterKey(posterKey);
        return asset;
    }
}
