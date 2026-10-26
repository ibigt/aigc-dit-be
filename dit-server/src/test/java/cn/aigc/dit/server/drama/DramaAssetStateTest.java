package cn.aigc.dit.server.drama;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.security.LoginUser;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.server.drama.entity.DramaAsset;
import cn.aigc.dit.server.drama.entity.DramaEpisode;
import cn.aigc.dit.server.drama.entity.DramaSeries;
import cn.aigc.dit.server.drama.mapper.DramaAssetGenerationMapper;
import cn.aigc.dit.server.drama.mapper.DramaAssetMapper;
import cn.aigc.dit.server.drama.mapper.DramaEpisodeMapper;
import cn.aigc.dit.server.drama.mapper.DramaSeriesMapper;
import cn.aigc.dit.server.drama.model.req.DramaAssetSaveReq;
import cn.aigc.dit.server.drama.text.DramaTextService;
import cn.aigc.dit.server.entity.AiModel;
import cn.aigc.dit.server.mapper.AiModelMapper;
import cn.aigc.dit.server.mapper.GenTaskMapper;
import cn.aigc.dit.server.service.AssetService;
import cn.aigc.dit.server.service.CreationService;
import cn.aigc.dit.server.service.GenerateService;
import cn.aigc.dit.server.service.MediaRetentionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 人物状态扩展（本体名·状态名）的形状约束：
 * 名字是状态的身份，标签由名字派生，状态资产继承本体外形并只叠加状态描述。
 */
@ExtendWith(MockitoExtension.class)
class DramaAssetStateTest {

    private static final String BASE_IDENTITY = "男性；青年；锐利眼神；冷峻气质；修长身形";

    @Mock
    private DramaAssetMapper assetMapper;
    @Mock
    private DramaAssetGenerationMapper assetGenerationMapper;
    @Mock
    private DramaEpisodeMapper episodeMapper;
    @Mock
    private DramaSeriesMapper seriesMapper;
    @Mock
    private AiModelMapper aiModelMapper;
    @Mock
    private GenTaskMapper genTaskMapper;
    @Mock
    private ObjectProvider<DramaTextService> textService;
    @Mock
    private GenerateService generateService;
    @Mock
    private AssetService libraryAssetService;
    @Mock
    private DramaStoryboardService storyboardService;
    @Mock
    private ArkCharacterReview characterReview;
    @Mock
    private CreationService creationService;
    @Mock
    private MediaRetentionService mediaRetention;

    private DramaAssetService service;

    @BeforeEach
    void setUp() {
        service = new DramaAssetService(
                assetMapper, assetGenerationMapper, episodeMapper, seriesMapper, aiModelMapper, genTaskMapper,
                textService, generateService, libraryAssetService, storyboardService, characterReview,
                creationService, mediaRetention);
        UserContext.set(new LoginUser(7L, "creator", "c@example.com", "创作者", LoginUser.ROLE_CREATOR));
        org.mockito.Mockito.lenient().when(textService.getIfAvailable()).thenReturn(null);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void stateTagFollowsTheNameSuffix() {
        assertEquals(DramaContract.STATE_TAG_BASE, DramaAssetDrafts.inferStateTag("洛白"));
        assertEquals(DramaContract.STATE_TAG_DREAM, DramaAssetDrafts.inferStateTag("洛白·入梦"));
        assertEquals(DramaContract.STATE_TAG_COSTUME, DramaAssetDrafts.inferStateTag("洛白·换装"));
        assertEquals(DramaContract.STATE_TAG_COSTUME, DramaAssetDrafts.inferStateTag("洛白·湿身"));
        assertEquals(DramaContract.STATE_TAG_CUSTOM, DramaAssetDrafts.inferStateTag("洛白·战损"));
        assertEquals("洛白", DramaAssetDrafts.baseCharacterName("洛白·湿身"));
        assertEquals("湿身", DramaAssetDrafts.stateNameOf("洛白·湿身"));
        assertEquals("", DramaAssetDrafts.stateNameOf("洛白"));
    }

    @Test
    void derivativeChangeUsesTheStateDescriptionNotTheIdentity() {
        String fromDescription = DramaAssetDrafts.derivativeChange(
                "洛白·湿身", DramaContract.STATE_TAG_COSTUME, "白衬衫湿透贴服", BASE_IDENTITY);
        assertEquals("白衬衫湿透贴服", fromDescription);

        String fallback = DramaAssetDrafts.derivativeChange(
                "洛白·湿身", DramaContract.STATE_TAG_COSTUME, "", BASE_IDENTITY);
        assertTrue(fallback.contains("湿透"));
        assertFalse(fallback.contains("锐利眼神"));
        assertFalse(fallback.contains(BASE_IDENTITY));
    }

    @Test
    void creatingAStateAssetAppendsTheStateNameAndDerivesTheTag() {
        DramaEpisode episode = ownedEpisode();
        when(episodeMapper.selectById(31L)).thenReturn(episode);
        when(seriesMapper.selectById(21L)).thenReturn(ownedSeries());
        when(assetMapper.selectList(any())).thenReturn(List.of(baseCharacter()));
        when(assetMapper.selectOne(any())).thenReturn(null);

        DramaAssetSaveReq req = new DramaAssetSaveReq();
        req.setName("洛白");
        req.setType("角色");
        req.setStateName("湿身");
        req.setDescription("白衬衫湿透贴服，头发湿哒哒贴着额头");

        service.create(31L, req);

        DramaAsset inserted = capturedInsert();
        assertEquals("洛白·湿身", inserted.getName());
        assertEquals(DramaContract.STATE_TAG_COSTUME, inserted.getStateTag());
        assertTrue(Boolean.TRUE.equals(inserted.getMustSplit()));
        // 状态资产不复制本体长句，只留「同一个人」的锁；本体外形在生图时继承。
        assertTrue(inserted.getIdentityInvariants().contains("同一张脸"));
        assertFalse(inserted.getIdentityInvariants().contains("锐利眼神"));
        // 状态描述是「相对本体的变化」，不能被设定图模板覆盖。
        assertEquals("白衬衫湿透贴服，头发湿哒哒贴着额头", inserted.getDescription());
        assertNotNull(inserted.getImagePrompt());
        assertTrue(inserted.getImagePrompt().contains("本状态外观：白衬衫湿透贴服"));
        assertTrue(inserted.getImagePrompt().contains(BASE_IDENTITY));
    }

    @Test
    void stateAssetWithoutDescriptionIsRejected() {
        DramaEpisode episode = ownedEpisode();
        when(episodeMapper.selectById(31L)).thenReturn(episode);

        DramaAssetSaveReq req = new DramaAssetSaveReq();
        req.setName("洛白");
        req.setType("角色");
        req.setStateName("湿身");

        BizException error = assertThrows(BizException.class, () -> service.create(31L, req));

        assertEquals("请填写状态描述，例如「穿红色晚礼服，头发扎起」", error.getMessage());
        verify(assetMapper, never()).insert(any(DramaAsset.class));
    }

    @Test
    void duplicateStateNameIsRejectedWithTheStateMessage() {
        DramaEpisode episode = ownedEpisode();
        when(episodeMapper.selectById(31L)).thenReturn(episode);
        DramaAsset existing = baseCharacter();
        existing.setId(11L);
        existing.setName("洛白·湿身");
        existing.setStateTag(DramaContract.STATE_TAG_COSTUME);
        when(assetMapper.selectList(any())).thenReturn(List.of(existing));

        DramaAssetSaveReq req = new DramaAssetSaveReq();
        req.setName("洛白");
        req.setType("角色");
        req.setStateName("湿身");
        req.setDescription("白衬衫湿透贴服");

        BizException error = assertThrows(BizException.class, () -> service.create(31L, req));

        assertEquals("「洛白」的「湿身」状态已经存在", error.getMessage());
        verify(assetMapper, never()).insert(any(DramaAsset.class));
    }

    @Test
    void creatingABaseCharacterKeepsThePlainName() {
        DramaEpisode episode = ownedEpisode();
        when(episodeMapper.selectById(31L)).thenReturn(episode);
        when(seriesMapper.selectById(21L)).thenReturn(ownedSeries());
        when(assetMapper.selectList(any())).thenReturn(List.of());
        when(assetMapper.selectOne(any())).thenReturn(null);

        DramaAssetSaveReq req = new DramaAssetSaveReq();
        req.setName("林夕");
        req.setType("角色");
        req.setDescription("女性；青年；齐肩短发；浅色风衣");

        service.create(31L, req);

        DramaAsset inserted = capturedInsert();
        assertEquals("林夕", inserted.getName());
        assertEquals(DramaContract.STATE_TAG_BASE, inserted.getStateTag());
        assertFalse(Boolean.TRUE.equals(inserted.getMustSplit()));
    }

    @Test
    void renamingAStateAssetReDerivesItsTag() {
        DramaAsset asset = baseCharacter();
        asset.setId(12L);
        asset.setName("洛白·湿身");
        asset.setStateTag(DramaContract.STATE_TAG_COSTUME);
        when(assetMapper.selectById(12L)).thenReturn(asset);
        when(seriesMapper.selectById(21L)).thenReturn(ownedSeries());
        when(assetMapper.selectList(any())).thenReturn(List.of(baseCharacter()));
        when(assetMapper.selectOne(any())).thenReturn(null);

        DramaAssetSaveReq req = new DramaAssetSaveReq();
        req.setName("洛白·入梦");

        service.update(12L, req);

        ArgumentCaptor<DramaAsset> captor = ArgumentCaptor.forClass(DramaAsset.class);
        verify(assetMapper).updateById(captor.capture());
        assertEquals("洛白·入梦", captor.getValue().getName());
        assertEquals(DramaContract.STATE_TAG_DREAM, captor.getValue().getStateTag());
    }

    private DramaAsset capturedInsert() {
        ArgumentCaptor<DramaAsset> captor = ArgumentCaptor.forClass(DramaAsset.class);
        verify(assetMapper).insert(captor.capture());
        return captor.getValue();
    }

    private static DramaAsset baseCharacter() {
        DramaAsset asset = new DramaAsset();
        asset.setId(9L);
        asset.setUserId(7L);
        asset.setSeriesId(21L);
        asset.setEpisodeId(31L);
        asset.setName("洛白");
        asset.setType(DramaContract.ASSET_TYPE_CHARACTER);
        asset.setStateTag(DramaContract.STATE_TAG_BASE);
        asset.setIdentityInvariants(BASE_IDENTITY);
        asset.setStatus(DramaContract.STATUS_LOCKED);
        return asset;
    }

    private static DramaEpisode ownedEpisode() {
        DramaEpisode episode = new DramaEpisode();
        episode.setId(31L);
        episode.setUserId(7L);
        episode.setSeriesId(21L);
        episode.setEpisodeNumber(1);
        episode.setName("第 1 集");
        episode.setStatus(DramaContract.STATUS_LOCKED);
        episode.setEngine(DramaContract.ENGINE_RULES);
        return episode;
    }

    private static DramaSeries ownedSeries() {
        DramaSeries series = new DramaSeries();
        series.setId(21L);
        series.setUserId(7L);
        series.setName("侦探社");
        series.setAspectRatio("16:9");
        series.setDefaultResolution("720P");
        return series;
    }
}
