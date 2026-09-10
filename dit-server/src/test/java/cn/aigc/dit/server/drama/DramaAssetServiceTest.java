package cn.aigc.dit.server.drama;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.security.LoginUser;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.server.drama.entity.DramaAsset;
import cn.aigc.dit.server.drama.entity.DramaEpisode;
import cn.aigc.dit.server.drama.entity.DramaSeries;
import cn.aigc.dit.server.drama.mapper.DramaAssetMapper;
import cn.aigc.dit.server.drama.mapper.DramaEpisodeMapper;
import cn.aigc.dit.server.drama.mapper.DramaSeriesMapper;
import cn.aigc.dit.server.drama.model.resp.DramaAssetResp;
import cn.aigc.dit.server.drama.text.DramaTextService;
import cn.aigc.dit.server.drama.text.ScriptDoc;
import cn.aigc.dit.server.drama.text.ScriptDocs;
import cn.aigc.dit.server.generate.GenerateRequests;
import cn.aigc.dit.server.mapper.AiModelMapper;
import cn.aigc.dit.server.mapper.GenTaskMapper;
import cn.aigc.dit.server.model.req.GenerateSubmitReq;
import cn.aigc.dit.server.model.resp.GenerateTaskResp;
import cn.aigc.dit.server.service.AssetService;
import cn.aigc.dit.server.service.GenerateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DramaAssetServiceTest {

    @Mock
    private DramaAssetMapper assetMapper;
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

    private DramaAssetService service;

    @BeforeEach
    void setUp() {
        service = new DramaAssetService(
                assetMapper, episodeMapper, seriesMapper, aiModelMapper, genTaskMapper,
                textService, generateService, libraryAssetService, storyboardService);
        UserContext.set(new LoginUser(7L, "creator", "c@example.com", "创作者", LoginUser.ROLE_CREATOR));
        org.mockito.Mockito.lenient().when(textService.getIfAvailable()).thenReturn(null);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void extractRequiresLockedScript() {
        DramaEpisode episode = ownedEpisode();
        episode.setStatus(DramaContract.STATUS_PENDING_CONFIRM);
        when(episodeMapper.selectById(31L)).thenReturn(episode);
        BizException error = assertThrows(BizException.class, () -> service.extractFromScript(31L));
        assertEquals("请先锁定分场剧本再提取资产", error.getMessage());
    }

    @Test
    void extractFromLockedScriptInsertsDrafts() {
        DramaEpisode episode = ownedEpisode();
        episode.setStatus(DramaContract.STATUS_LOCKED);
        episode.setScript(ScriptDocs.toMap(sampleDoc()));
        when(episodeMapper.selectById(31L)).thenReturn(episode);
        when(seriesMapper.selectById(21L)).thenReturn(ownedSeries());
        when(assetMapper.selectList(any())).thenReturn(List.of());
        when(assetMapper.insert(any(DramaAsset.class))).thenAnswer(invocation -> {
            DramaAsset asset = invocation.getArgument(0);
            asset.setId(System.identityHashCode(asset) * 1L);
            return 1;
        });

        List<DramaAssetResp> assets = service.extractFromScript(31L);
        ArgumentCaptor<DramaAsset> captor = ArgumentCaptor.forClass(DramaAsset.class);
        verify(assetMapper, org.mockito.Mockito.atLeast(4)).insert(captor.capture());
        assertTrue(captor.getAllValues().stream().anyMatch(item -> "洛白".equals(item.getName())));
        assertTrue(captor.getAllValues().stream().anyMatch(item -> "古铜怀表".equals(item.getName())));
        assertTrue(captor.getAllValues().stream().anyMatch(item -> "幻梦侦探社".equals(item.getName())));
        assertTrue(captor.getAllValues().stream().anyMatch(item ->
                "洛白·入梦".equals(item.getName()) && DramaContract.STATE_TAG_DREAM.equals(item.getStateTag())));
        assertTrue(captor.getAllValues().stream().anyMatch(item ->
                "洛白".equals(item.getName())
                        && item.getImagePrompt() != null
                        && item.getImagePrompt().contains("三视图")
                        && item.getImagePrompt().contains("二次元漫画风")));
        assertTrue(captor.getAllValues().stream().anyMatch(item ->
                "幻梦侦探社".equals(item.getName())
                        && item.getImagePrompt() != null
                        && item.getImagePrompt().contains("不要出现人物")));
        assertFalse(assets.stream().anyMatch(item -> Boolean.TRUE.equals(item.getReferenceEligible())));
    }

    @Test
    void lockRequiresImage() {
        when(assetMapper.selectById(9L)).thenReturn(ownedAsset());
        BizException error = assertThrows(BizException.class, () -> service.lock(9L));
        assertEquals("请先上传或生成参考图再锁定", error.getMessage());
    }

    @Test
    void lockWithImageBumpsIdentityVersion() {
        DramaAsset asset = ownedAsset();
        asset.setImageStorageKey("users/7/assets/luobai.png");
        asset.setImagePrompt("中性背景全身参考图，洛白");
        when(assetMapper.selectById(9L)).thenReturn(asset);

        DramaAssetResp resp = service.lock(9L);
        assertEquals(DramaContract.STATUS_LOCKED, resp.getStatus());
        assertEquals(1, resp.getIdentityVersion());
        assertTrue(resp.getReferenceEligible());
        verify(assetMapper).updateById(asset);
        verify(storyboardService).invalidateByAsset(31L, 9L);
    }

    @Test
    void generateWithoutBoundImageModelRejected() {
        DramaAsset asset = ownedAsset();
        asset.setImagePrompt("中性背景全身参考图");
        when(assetMapper.selectById(9L)).thenReturn(asset);
        DramaSeries series = ownedSeries();
        when(seriesMapper.selectById(21L)).thenReturn(series);

        BizException error = assertThrows(BizException.class, () -> service.generateImage(9L));
        assertEquals("请先在资产页顶部选择图像模型", error.getMessage());
        verify(generateService, never()).submit(any(GenerateSubmitReq.class), any());
    }

    @Test
    void generateUsesSeriesImageModelNotPerAsset() {
        DramaAsset asset = ownedAsset();
        asset.setImagePrompt("中性背景全身参考图，洛白");
        when(assetMapper.selectById(9L)).thenReturn(asset);
        DramaSeries series = ownedSeries();
        series.setImageModelId(12L);
        series.setImageAdapterKey("gpt-image-2");
        when(seriesMapper.selectById(21L)).thenReturn(series);
        cn.aigc.dit.server.entity.AiModel model = new cn.aigc.dit.server.entity.AiModel();
        model.setId(12L);
        model.setName("GPT Image 2");
        model.setCategory("image");
        model.setEnabled(true);
        model.setCapabilities(List.of("image", "t2i"));
        when(aiModelMapper.selectById(12L)).thenReturn(model);
        when(genTaskMapper.selectCount(any())).thenReturn(0L);
        GenerateTaskResp task = new GenerateTaskResp();
        task.setId(100L);
        task.setStatus("PENDING");
        when(generateService.submit(any(GenerateSubmitReq.class), any())).thenReturn(task);

        service.generateImage(9L);

        ArgumentCaptor<GenerateSubmitReq> reqCaptor = ArgumentCaptor.forClass(GenerateSubmitReq.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> extraCaptor = ArgumentCaptor.forClass(Map.class);
        verify(generateService).submit(reqCaptor.capture(), extraCaptor.capture());
        assertEquals(12L, reqCaptor.getValue().getModelId());
        assertEquals("image", reqCaptor.getValue().getCategory());
        assertEquals("1K", reqCaptor.getValue().getResolution());
        assertEquals(GenerateRequests.SOURCE_DRAMA, extraCaptor.getValue().get("source"));
        assertEquals(9L, extraCaptor.getValue().get("assetId"));
        assertEquals(31L, extraCaptor.getValue().get("episodeId"));
        assertEquals(100L, asset.getGenTaskId());
    }

    @Test
    void unlockedAssetIsNotReferenceEligible() {
        DramaAsset asset = ownedAsset();
        asset.setImageStorageKey("users/7/assets/a.png");
        asset.setStatus(DramaContract.STATUS_PENDING_CONFIRM);
        when(assetMapper.selectList(any())).thenReturn(List.of(asset));
        when(episodeMapper.selectById(31L)).thenReturn(ownedEpisode());

        List<DramaAssetResp> list = service.listByEpisode(31L);
        assertFalse(list.get(0).getReferenceEligible());
    }

    @Test
    void regeneratingUnlockedAssetReplacesLibraryImage() {
        DramaAsset asset = ownedAsset();
        asset.setStatus(DramaContract.STATUS_PENDING_CONFIRM);
        asset.setImageAssetId(41L);
        asset.setImageStorageKey("users/7/assets/old.png");
        asset.setGenTaskId(100L);
        when(assetMapper.selectById(9L)).thenReturn(asset);
        GenerateTaskResp task = new GenerateTaskResp();
        task.setId(100L);
        task.setStatus("SUCCEEDED");
        task.setOutputStorageKey("users/7/gen/new.png");
        task.setOutputKeys(List.of("users/7/gen/new.png"));
        when(generateService.getMine(100L)).thenReturn(task);
        cn.aigc.dit.server.model.resp.AssetResp imported = new cn.aigc.dit.server.model.resp.AssetResp();
        imported.setId(42L);
        imported.setStorageKey("users/7/gen/new.png");
        when(libraryAssetService.importFromStorage(any(), any(), any(), any(), any())).thenReturn(imported);

        service.getGenerateTask(9L, 100L);

        verify(libraryAssetService).deleteMine(41L);
        assertEquals(42L, asset.getImageAssetId());
        assertEquals("users/7/gen/new.png", asset.getImageStorageKey());
    }

    @Test
    void regeneratingLockedAssetKeepsPreviousLibraryImage() {
        DramaAsset asset = ownedAsset();
        asset.setStatus(DramaContract.STATUS_LOCKED);
        asset.setImageAssetId(41L);
        asset.setImageStorageKey("users/7/assets/old.png");
        asset.setGenTaskId(100L);
        when(assetMapper.selectById(9L)).thenReturn(asset);
        GenerateTaskResp task = new GenerateTaskResp();
        task.setId(100L);
        task.setStatus("SUCCEEDED");
        task.setOutputStorageKey("users/7/gen/new.png");
        task.setOutputKeys(List.of("users/7/gen/new.png"));
        when(generateService.getMine(100L)).thenReturn(task);
        cn.aigc.dit.server.model.resp.AssetResp imported = new cn.aigc.dit.server.model.resp.AssetResp();
        imported.setId(42L);
        imported.setStorageKey("users/7/gen/new.png");
        when(libraryAssetService.importFromStorage(any(), any(), any(), any(), any())).thenReturn(imported);

        service.getGenerateTask(9L, 100L);

        verify(libraryAssetService, never()).deleteMine(any());
        assertEquals(42L, asset.getImageAssetId());
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

    private static DramaAsset ownedAsset() {
        DramaAsset asset = new DramaAsset();
        asset.setId(9L);
        asset.setUserId(7L);
        asset.setSeriesId(21L);
        asset.setEpisodeId(31L);
        asset.setName("洛白");
        asset.setType(DramaContract.ASSET_TYPE_CHARACTER);
        asset.setStateTag(DramaContract.STATE_TAG_BASE);
        asset.setStatus(DramaContract.STATUS_NOT_STARTED);
        asset.setIdentityVersion(0);
        return asset;
    }

    private static ScriptDoc sampleDoc() {
        ScriptDoc doc = new ScriptDoc();
        doc.setTitle("幻梦侦探社");
        ScriptDoc.Scene scene = new ScriptDoc.Scene();
        scene.setIndex(1);
        scene.setCharacters(List.of("洛白", "夏安", "洛白·入梦"));
        scene.setProps(List.of("古铜怀表"));
        scene.setLocation("幻梦侦探社");
        doc.setScenes(List.of(scene));
        return doc;
    }
}
