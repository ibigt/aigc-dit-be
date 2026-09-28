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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssetServiceListOrderTest {

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
    void listMineAppliesTimeDescByDefault() {
        when(assetMapper.selectList(any())).thenReturn(List.of());

        service.listMine("image", "sunset", null);

        verify(assetMapper).selectList(any());
    }

    @Test
    void listMineAppliesNameAscending() {
        when(assetMapper.selectList(any())).thenReturn(List.of(asset(1L, "banana"), asset(2L, "apple")));

        List<AssetResp> result = service.listMine("all", null, "name_asc");

        assertEquals(2, result.size());
        assertEquals("banana", result.get(0).getName());
        assertEquals("apple", result.get(1).getName());
    }

    @Test
    void listMineAppliesDurationDesc() {
        Asset longClip = asset(12L, "long");
        longClip.setDurationMs(60_000L);
        when(assetMapper.selectList(any())).thenReturn(List.of(longClip));

        List<AssetResp> result = service.listMine("video", null, "duration_desc");

        assertEquals(1, result.size());
        assertEquals("long", result.get(0).getName());
        assertEquals(60_000L, result.get(0).getDurationMs());
    }

    private static Asset asset(Long id, String name) {
        Asset a = new Asset();
        a.setId(id);
        a.setUserId(7L);
        a.setName(name);
        a.setKind("image");
        a.setStorageKey("users/7/assets/" + name + ".png");
        a.setSizeBytes(0L);
        return a;
    }
}
