package cn.aigc.dit.server.drama;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.security.LoginUser;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.server.drama.entity.DramaAsset;
import cn.aigc.dit.server.drama.entity.DramaAssetGeneration;
import cn.aigc.dit.server.drama.entity.DramaEpisode;
import cn.aigc.dit.server.drama.entity.DramaSeries;
import cn.aigc.dit.server.drama.mapper.DramaAssetGenerationMapper;
import cn.aigc.dit.server.drama.mapper.DramaAssetMapper;
import cn.aigc.dit.server.drama.mapper.DramaEpisodeMapper;
import cn.aigc.dit.server.drama.mapper.DramaSeriesMapper;
import cn.aigc.dit.server.drama.model.resp.DramaAssetResp;
import cn.aigc.dit.server.drama.text.DramaTextService;
import cn.aigc.dit.server.drama.text.ScriptDoc;
import cn.aigc.dit.server.drama.text.ScriptDocs;
import cn.aigc.dit.server.entity.GenTask;
import cn.aigc.dit.server.generate.GenerateRequests;
import cn.aigc.dit.server.mapper.AiModelMapper;
import cn.aigc.dit.server.mapper.GenTaskMapper;
import cn.aigc.dit.server.model.req.GenerateSubmitReq;
import cn.aigc.dit.server.model.resp.AssetResp;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DramaAssetServiceTest {

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

    private DramaAssetService service;
    private final AtomicLong generationIds = new AtomicLong(500);

    @BeforeEach
    void setUp() {
        service = new DramaAssetService(
                assetMapper, assetGenerationMapper, episodeMapper, seriesMapper, aiModelMapper, genTaskMapper,
                textService, generateService, libraryAssetService, storyboardService, characterReview);
        UserContext.set(new LoginUser(7L, "creator", "c@example.com", "创作者", LoginUser.ROLE_CREATOR));
        org.mockito.Mockito.lenient().when(textService.getIfAvailable()).thenReturn(null);
        org.mockito.Mockito.lenient().when(assetGenerationMapper.insert(any(DramaAssetGeneration.class))).thenAnswer(invocation -> {
            DramaAssetGeneration generation = invocation.getArgument(0);
            generation.setId(generationIds.incrementAndGet());
            return 1;
        });
        org.mockito.Mockito.lenient().when(assetGenerationMapper.selectCount(any())).thenReturn(0L);
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

        List<DramaAssetResp> assets = service.extractFromScript(31L, false);
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
                        && item.getImagePrompt().contains("二次元漫画风")
                        && item.getImagePrompt().contains("不要剧情场面")
                        && !item.getImagePrompt().contains("首次出场")));
        assertTrue(captor.getAllValues().stream().anyMatch(item ->
                "幻梦侦探社".equals(item.getName())
                        && item.getImagePrompt() != null
                        && item.getImagePrompt().contains("不要出现人物")));
        assertFalse(assets.stream().anyMatch(item -> Boolean.TRUE.equals(item.getReferenceEligible())));
    }

    @Test
    void llmAssetExtractionFallsBackToDeterministicSkeletonWhenTextServiceIsMissing() {
        DramaEpisode episode = ownedEpisode();
        episode.setStatus(DramaContract.STATUS_LOCKED);
        episode.setScript(ScriptDocs.toMap(sampleDoc()));
        when(episodeMapper.selectById(31L)).thenReturn(episode);
        when(seriesMapper.selectById(21L)).thenReturn(ownedSeries());

        service.extractFromScript(31L, true);

        verify(assetMapper, atLeastOnce()).insert(any(DramaAsset.class));
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
        when(seriesMapper.selectById(21L)).thenReturn(ownedSeries());

        DramaAssetResp resp = service.lock(9L);
        assertEquals(DramaContract.STATUS_LOCKED, resp.getStatus());
        assertEquals(1, resp.getIdentityVersion());
        assertTrue(resp.getReferenceEligible());
        verify(assetMapper, org.mockito.Mockito.atLeastOnce()).updateById(asset);
        verify(storyboardService).invalidateByAsset(31L, 9L);
    }

    @Test
    void lockRejectsAttachedRunningTask() {
        DramaAsset asset = ownedAsset();
        asset.setImageStorageKey("users/7/assets/luobai.png");
        asset.setGenTaskId(100L);
        when(assetMapper.selectById(9L)).thenReturn(asset);
        GenTask running = new GenTask();
        running.setId(100L);
        running.setStatus("RUNNING");
        when(genTaskMapper.selectById(100L)).thenReturn(running);

        BizException error = assertThrows(BizException.class, () -> service.lock(9L));
        assertEquals("图片仍在生成中，请等待完成后再锁定", error.getMessage());
        verify(assetMapper, never()).updateById(asset);
    }

    @Test
    void lockClearsTerminalTaskAssociation() {
        DramaAsset asset = ownedAsset();
        asset.setImageStorageKey("users/7/assets/luobai.png");
        asset.setImagePrompt("中性背景全身参考图，洛白");
        asset.setGenTaskId(100L);
        when(assetMapper.selectById(9L)).thenReturn(asset);
        when(seriesMapper.selectById(21L)).thenReturn(ownedSeries());
        GenTask done = new GenTask();
        done.setId(100L);
        done.setStatus("SUCCEEDED");
        when(genTaskMapper.selectById(100L)).thenReturn(done);

        DramaAssetResp resp = service.lock(9L);

        assertEquals(DramaContract.STATUS_LOCKED, resp.getStatus());
        assertNull(asset.getGenTaskId());
        verify(assetMapper, org.mockito.Mockito.atLeastOnce()).updateById(asset);
    }

    @Test
    void generateWithoutBoundImageModelRejected() {
        DramaAsset asset = ownedAsset();
        asset.setImagePrompt("中性背景全身参考图");
        when(assetMapper.selectById(9L)).thenReturn(asset);
        DramaSeries series = ownedSeries();
        when(seriesMapper.selectById(21L)).thenReturn(series);

        BizException error = assertThrows(BizException.class, () -> service.generateImage(9L));
        assertEquals("请先选择图像模型", error.getMessage());
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
        assertTrue(reqCaptor.getValue().getPrompt().contains("洛白角色设定图"));
        assertTrue(reqCaptor.getValue().getPrompt().contains("同一张图内三视图排列"));
        assertTrue(reqCaptor.getValue().getReferences() == null || reqCaptor.getValue().getReferences().isEmpty());
    }

    @Test
    void costumeGenerateUsesBaseSheetAsReferenceAndOnlyDescribesTheChange() {
        DramaAsset wet = ownedAsset();
        wet.setId(10L);
        wet.setName("洛白·湿身");
        wet.setStateTag(DramaContract.STATE_TAG_COSTUME);
        wet.setDescription("外衣与发尾湿透，贴在身上");
        wet.setIdentityInvariants("与主身份同一张脸和发型；禁止写成另一个人");
        wet.setImagePrompt("洛白·湿身角色设定图。换装独立成图。");
        when(assetMapper.selectById(10L)).thenReturn(wet);
        DramaAsset base = ownedAsset();
        base.setImageStorageKey("users/7/assets/luobai.png");
        when(assetMapper.selectList(any())).thenReturn(List.of(base));
        DramaSeries series = ownedSeries();
        series.setImageModelId(12L);
        series.setImageAdapterKey("gpt-image-2");
        when(seriesMapper.selectById(21L)).thenReturn(series);
        cn.aigc.dit.server.entity.AiModel model = new cn.aigc.dit.server.entity.AiModel();
        model.setId(12L);
        model.setCategory("image");
        model.setEnabled(true);
        model.setCapabilities(List.of("image", "t2i", "i2i"));
        when(aiModelMapper.selectById(12L)).thenReturn(model);
        when(genTaskMapper.selectCount(any())).thenReturn(0L);
        GenerateTaskResp task = new GenerateTaskResp();
        task.setId(101L);
        task.setStatus("PENDING");
        when(generateService.submit(any(GenerateSubmitReq.class), any())).thenReturn(task);

        service.generateImage(10L);

        ArgumentCaptor<GenerateSubmitReq> reqCaptor = ArgumentCaptor.forClass(GenerateSubmitReq.class);
        verify(generateService).submit(reqCaptor.capture(), any());
        String prompt = reqCaptor.getValue().getPrompt();
        assertTrue(prompt.contains("保持原图的版式、构图、比例和背景不变"));
        assertTrue(prompt.contains("变化：外衣与发尾湿透，贴在身上。"));
        assertFalse(prompt.contains("三视图"));
        assertEquals(1, reqCaptor.getValue().getReferences().size());
        assertEquals("users/7/assets/luobai.png", reqCaptor.getValue().getReferences().get(0).getStorageKey());
        assertEquals("image", reqCaptor.getValue().getReferences().get(0).getType());
    }

    @Test
    void assetWithImageIsReferenceEligibleWithoutLock() {
        DramaAsset asset = ownedAsset();
        asset.setImageStorageKey("users/7/assets/a.png");
        asset.setStatus(DramaContract.STATUS_PENDING_CONFIRM);
        when(assetMapper.selectList(any())).thenReturn(List.of(asset));
        when(episodeMapper.selectById(31L)).thenReturn(ownedEpisode());
        when(seriesMapper.selectById(21L)).thenReturn(ownedSeries());

        List<DramaAssetResp> list = service.listByEpisode(31L);
        assertTrue(list.get(0).getReferenceEligible());
    }

    @Test
    void listByEpisodeRepairsIncompleteCharacterPrompt() {
        DramaAsset asset = ownedAsset();
        asset.setStatus(DramaContract.STATUS_PENDING_CONFIRM);
        asset.setImagePrompt("浅灰背景的角色三视图：正面全身、侧面半身、面部特写");
        asset.setDescription("浅灰背景的角色三视图：正面全身、侧面半身、面部特写");
        asset.setIdentityInvariants("男性；青年；锐利眼神；冷峻气质；修长身形");
        when(assetMapper.selectList(any())).thenReturn(List.of(asset));
        when(episodeMapper.selectById(31L)).thenReturn(ownedEpisode());
        DramaSeries series = ownedSeries();
        series.setStyleKey(DramaStyles.LIVE_REALISM);
        when(seriesMapper.selectById(21L)).thenReturn(series);

        List<DramaAssetResp> list = service.listByEpisode(31L);
        String prompt = list.get(0).getImagePrompt();
        assertTrue(prompt.contains("洛白角色设定图"));
        assertTrue(prompt.contains("单独站立"));
        // 现代写实风：压缩定妆转面骨架
        assertTrue(prompt.contains("真人实拍定妆"));
        assertTrue(prompt.contains("正脸近景"));
        assertTrue(prompt.contains("严格 90 度侧面"));
        assertTrue(prompt.contains("纯白无缝背景"));
        assertTrue(!prompt.contains("固定四格设定拼版"));
        assertTrue(!prompt.contains("面部特写"));
        assertTrue(prompt.contains("现代写实风"));
        assertTrue(prompt.length() > 80);
        assertTrue(prompt.contains("外形与服装必须符合：男性；青年；锐利眼神；冷峻气质；修长身形"));
        // 方案 A：description 不再被长模板覆盖，旧模板 description 降级为外形摘要
        assertEquals("男性；青年；锐利眼神；冷峻气质；修长身形", asset.getDescription());
        verify(assetMapper).updateById(asset);
    }

    @Test
    void generateAlwaysRebuildsTemplateIgnoringHandEditedImagePrompt() {
        DramaAsset asset = ownedAsset();
        asset.setImagePrompt("用户手改的整段提示词，不要三视图，只要半身特写油画风");
        asset.setIdentityInvariants("男性；青年；黑碎盖；深灰风衣");
        asset.setDescription("男性；青年；黑碎盖；深灰风衣");
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
        verify(generateService).submit(reqCaptor.capture(), any());
        String prompt = reqCaptor.getValue().getPrompt();
        assertTrue(prompt.contains("洛白角色设定图"));
        assertTrue(prompt.contains("同一张图内三视图排列"));
        assertTrue(prompt.contains("外形与服装必须符合：男性；青年；黑碎盖；深灰风衣"));
        assertFalse(prompt.contains("油画风"));
        assertEquals("男性；青年；黑碎盖；深灰风衣", asset.getDescription());
    }

    @Test
    void uploadImageStoresWithoutLibraryRow() {
        DramaAsset asset = ownedAsset();
        when(assetMapper.selectById(9L)).thenReturn(asset);
        when(libraryAssetService.putOwnedFile(any(), any())).thenReturn("users/7/drama/21/x.png");
        org.springframework.mock.web.MockMultipartFile file = new org.springframework.mock.web.MockMultipartFile(
                "file", "luobai.png", "image/png", new byte[] {1, 2, 3});

        DramaAssetResp resp = service.uploadImage(9L, file);

        verify(libraryAssetService, never()).upload(any());
        verify(libraryAssetService).putOwnedFile(file, "drama/21");
        assertNull(asset.getImageAssetId());
        assertEquals("users/7/drama/21/x.png", asset.getImageStorageKey());
        assertEquals("users/7/drama/21/x.png", resp.getImageStorageKey());
    }

    @Test
    void uploadVoiceStoresInMinioAndIndexesLibraryWhenBoundModelSupportsAudio() {
        DramaAsset asset = ownedAsset();
        when(assetMapper.selectById(9L)).thenReturn(asset);
        org.springframework.mock.web.MockMultipartFile file = new org.springframework.mock.web.MockMultipartFile(
                "file", "voice.wav", "audio/wav", new byte[]{1, 2, 3});
        AssetResp libraryAsset = new AssetResp();
        libraryAsset.setStorageKey("users/7/assets/voice.wav");
        libraryAsset.setMimeType("audio/wav");
        when(libraryAssetService.upload(file)).thenReturn(libraryAsset);

        DramaAssetResp resp = service.uploadVoiceReference(9L, file);

        verify(libraryAssetService).upload(file);
        verify(libraryAssetService, never()).putOwnedFile(any(), any());
        assertEquals("users/7/assets/voice.wav", resp.getVoiceReferenceKey());
        assertEquals("audio/wav", resp.getVoiceReferenceMime());
    }

    @Test
    void bindVoiceUsesOwnedAudioFromLibrary() {
        DramaAsset asset = ownedAsset();
        cn.aigc.dit.server.entity.Asset libraryAsset = new cn.aigc.dit.server.entity.Asset();
        libraryAsset.setId(55L);
        libraryAsset.setKind("audio");
        libraryAsset.setMimeType("audio/mpeg");
        libraryAsset.setStorageKey("users/7/assets/voice.mp3");
        when(assetMapper.selectById(9L)).thenReturn(asset);
        when(libraryAssetService.requireOwned(55L)).thenReturn(libraryAsset);

        DramaAssetResp resp = service.bindVoiceReference(9L, 55L);

        assertEquals("users/7/assets/voice.mp3", resp.getVoiceReferenceKey());
        assertEquals("audio/mpeg", resp.getVoiceReferenceMime());
        verify(assetMapper).updateById(asset);
    }

    @Test
    void bindImageUsesOwnedImageFromLibrary() {
        DramaAsset asset = ownedAsset();
        cn.aigc.dit.server.entity.Asset libraryAsset = new cn.aigc.dit.server.entity.Asset();
        libraryAsset.setId(66L);
        libraryAsset.setName("库图");
        libraryAsset.setKind("image");
        libraryAsset.setMimeType("image/png");
        libraryAsset.setStorageKey("users/7/assets/ref.png");
        when(assetMapper.selectById(9L)).thenReturn(asset);
        when(libraryAssetService.requireOwned(66L)).thenReturn(libraryAsset);
        when(assetGenerationMapper.selectCount(any())).thenReturn(0L);

        DramaAssetResp resp = service.bindImageFromLibrary(9L, 66L);

        assertEquals("users/7/assets/ref.png", resp.getImageStorageKey());
        assertEquals(66L, asset.getImageAssetId());
        ArgumentCaptor<DramaAssetGeneration> captor = ArgumentCaptor.forClass(DramaAssetGeneration.class);
        verify(assetGenerationMapper).insert(captor.capture());
        assertEquals("upload", captor.getValue().getSource());
        assertEquals(66L, captor.getValue().getLibraryAssetId());
        assertTrue(captor.getValue().getLabel().startsWith("引用"));
        verify(assetMapper).updateById(asset);
    }

    @Test
    void uploadVoiceIsAllowedBeforeVideoModelSelection() {
        DramaAsset asset = ownedAsset();
        when(assetMapper.selectById(9L)).thenReturn(asset);
        org.springframework.mock.web.MockMultipartFile file = new org.springframework.mock.web.MockMultipartFile(
                "file", "voice.mp3", "audio/mpeg", new byte[]{1});
        AssetResp libraryAsset = new AssetResp();
        libraryAsset.setStorageKey("users/7/assets/voice.mp3");
        libraryAsset.setMimeType("audio/mpeg");
        when(libraryAssetService.upload(file)).thenReturn(libraryAsset);

        DramaAssetResp resp = service.uploadVoiceReference(9L, file);

        assertEquals("users/7/assets/voice.mp3", resp.getVoiceReferenceKey());
        verify(libraryAssetService).upload(file);
    }

    @Test
    void regeneratingUnlockedAssetStoresKeyWithoutLibrary() {
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

        service.getGenerateTask(9L, 100L);

        verify(libraryAssetService, never()).importFromStorage(any(), any(), any(), any(), any());
        verify(libraryAssetService, never()).deleteMine(any());
        assertNull(asset.getImageAssetId());
        assertEquals("users/7/gen/new.png", asset.getImageStorageKey());
        assertNotNull(asset.getSelectedGenerationId());
    }

    @Test
    void regeneratingLockedAssetUnlocksAndSelectsNewGeneration() {
        DramaAsset asset = ownedAsset();
        asset.setStatus(DramaContract.STATUS_LOCKED);
        asset.setImageAssetId(41L);
        asset.setImageStorageKey("users/7/assets/old.png");
        asset.setSelectedGenerationId(88L);
        asset.setGenTaskId(100L);
        when(assetMapper.selectById(9L)).thenReturn(asset);
        GenerateTaskResp task = new GenerateTaskResp();
        task.setId(100L);
        task.setStatus("SUCCEEDED");
        task.setOutputStorageKey("users/7/gen/new.png");
        task.setOutputKeys(List.of("users/7/gen/new.png"));
        when(generateService.getMine(100L)).thenReturn(task);

        service.getGenerateTask(9L, 100L);

        verify(libraryAssetService, never()).importFromStorage(any(), any(), any(), any(), any());
        verify(libraryAssetService, never()).deleteMine(any());
        assertNull(asset.getImageAssetId());
        assertEquals("users/7/gen/new.png", asset.getImageStorageKey());
        assertEquals(DramaContract.STATUS_PENDING_CONFIRM, asset.getStatus());
        assertNotNull(asset.getSelectedGenerationId());
        assertTrue(asset.getSelectedGenerationId() > 88L);
        verify(storyboardService).invalidateByAsset(31L, 9L);
    }

    @Test
    void selectGenerationUsesChosenImageEvenWhenLocked() {
        DramaAsset asset = ownedAsset();
        asset.setStatus(DramaContract.STATUS_LOCKED);
        asset.setSelectedGenerationId(1L);
        asset.setImageStorageKey("users/7/gen/old.png");
        when(assetMapper.selectById(9L)).thenReturn(asset);
        DramaAssetGeneration chosen = new DramaAssetGeneration();
        chosen.setId(2L);
        chosen.setAssetId(9L);
        chosen.setUserId(7L);
        chosen.setStorageKey("users/7/gen/picked.png");
        when(assetGenerationMapper.selectById(2L)).thenReturn(chosen);

        DramaAssetResp resp = service.selectGeneration(9L, 2L);

        assertEquals(2L, asset.getSelectedGenerationId());
        assertEquals("users/7/gen/picked.png", asset.getImageStorageKey());
        assertEquals(Boolean.TRUE, resp.getReferenceEligible());
        verify(storyboardService).invalidateByAsset(31L, 9L);
    }

    @Test
    void applyTaskResultDoesNotDuplicateAfterSelectingOlderAssetGeneration() {
        DramaAsset asset = ownedAsset();
        asset.setStatus(DramaContract.STATUS_PENDING_CONFIRM);
        asset.setSelectedGenerationId(1L);
        asset.setImageStorageKey("users/7/gen/old.png");
        asset.setGenTaskId(100L);
        when(assetMapper.selectById(9L)).thenReturn(asset);
        DramaAssetGeneration newer = new DramaAssetGeneration();
        newer.setId(2L);
        newer.setAssetId(9L);
        newer.setUserId(7L);
        newer.setStorageKey("users/7/gen/new.png");
        newer.setGenTaskId(100L);
        when(assetGenerationMapper.selectOne(any())).thenReturn(newer);
        GenerateTaskResp task = new GenerateTaskResp();
        task.setId(100L);
        task.setStatus("SUCCEEDED");
        task.setOutputStorageKey("users/7/gen/new.png");
        task.setOutputKeys(List.of("users/7/gen/new.png"));
        when(generateService.getMine(100L)).thenReturn(task);

        service.getGenerateTask(9L, 100L);

        verify(assetGenerationMapper, never()).insert(any(DramaAssetGeneration.class));
        assertEquals(1L, asset.getSelectedGenerationId());
        assertEquals("users/7/gen/old.png", asset.getImageStorageKey());
    }

    @Test
    void lockedAssetCanDeleteSelectedGeneration() {
        DramaAsset asset = ownedAsset();
        asset.setStatus(DramaContract.STATUS_LOCKED);
        asset.setSelectedGenerationId(1L);
        when(assetMapper.selectById(9L)).thenReturn(asset);

        DramaAssetGeneration selected = new DramaAssetGeneration();
        selected.setId(1L);
        selected.setAssetId(9L);
        selected.setUserId(7L);
        selected.setStorageKey("users/7/gen/sel.png");
        DramaAssetGeneration other = new DramaAssetGeneration();
        other.setId(2L);
        other.setAssetId(9L);
        other.setUserId(7L);
        other.setStorageKey("users/7/gen/other.png");
        when(assetGenerationMapper.selectById(1L)).thenReturn(selected);
        when(assetGenerationMapper.selectList(any())).thenReturn(List.of(other));

        service.deleteGeneration(9L, 1L);

        verify(assetGenerationMapper).deleteById(1L);
        assertEquals(2L, asset.getSelectedGenerationId());
        assertEquals("users/7/gen/other.png", asset.getImageStorageKey());
        verify(storyboardService).invalidateByAsset(31L, 9L);
    }

    @Test
    void unlockedDeleteSelectedReselectsLatestRemaining() {
        DramaAsset asset = ownedAsset();
        asset.setStatus(DramaContract.STATUS_PENDING_CONFIRM);
        asset.setSelectedGenerationId(2L);
        asset.setImageStorageKey("users/7/gen/new.png");
        when(assetMapper.selectById(9L)).thenReturn(asset);

        DramaAssetGeneration older = new DramaAssetGeneration();
        older.setId(1L);
        older.setAssetId(9L);
        older.setUserId(7L);
        older.setStorageKey("users/7/gen/old.png");
        older.setLibraryAssetId(55L);
        DramaAssetGeneration selected = new DramaAssetGeneration();
        selected.setId(2L);
        selected.setAssetId(9L);
        selected.setUserId(7L);
        selected.setStorageKey("users/7/gen/new.png");
        when(assetGenerationMapper.selectById(2L)).thenReturn(selected);
        when(assetGenerationMapper.selectList(any())).thenReturn(List.of(older));

        service.deleteGeneration(9L, 2L);

        verify(assetGenerationMapper).deleteById(2L);
        assertEquals(1L, asset.getSelectedGenerationId());
        assertEquals("users/7/gen/old.png", asset.getImageStorageKey());
        assertEquals(55L, asset.getImageAssetId());
        verify(assetMapper).updateById(asset);
    }

    @Test
    void deleteSelectedRetargetsGenTaskSoRefreshCannotRecreateIt() {
        DramaAsset asset = ownedAsset();
        asset.setStatus(DramaContract.STATUS_PENDING_CONFIRM);
        asset.setSelectedGenerationId(2L);
        asset.setGenTaskId(287L);
        asset.setImageStorageKey("users/7/outputs/287/1.png");
        when(assetMapper.selectById(9L)).thenReturn(asset);

        DramaAssetGeneration older = new DramaAssetGeneration();
        older.setId(1L);
        older.setAssetId(9L);
        older.setUserId(7L);
        older.setGenTaskId(249L);
        older.setStorageKey("users/7/outputs/249/1.png");
        DramaAssetGeneration selected = new DramaAssetGeneration();
        selected.setId(2L);
        selected.setAssetId(9L);
        selected.setUserId(7L);
        selected.setGenTaskId(287L);
        selected.setStorageKey("users/7/outputs/287/1.png");
        when(assetGenerationMapper.selectById(2L)).thenReturn(selected);
        when(assetGenerationMapper.selectList(any())).thenReturn(List.of(older));

        service.deleteGeneration(9L, 2L);

        assertEquals(249L, asset.getGenTaskId());
        assertEquals("users/7/outputs/249/1.png", asset.getImageStorageKey());
        verify(assetGenerationMapper).deleteById(2L);
    }

    @Test
    void unlockedDeleteSelectedClearsWhenNoRemaining() {
        DramaAsset asset = ownedAsset();
        asset.setStatus(DramaContract.STATUS_PENDING_CONFIRM);
        asset.setSelectedGenerationId(2L);
        asset.setImageStorageKey("users/7/gen/only.png");
        when(assetMapper.selectById(9L)).thenReturn(asset);
        DramaAssetGeneration selected = new DramaAssetGeneration();
        selected.setId(2L);
        selected.setAssetId(9L);
        selected.setUserId(7L);
        selected.setStorageKey("users/7/gen/only.png");
        when(assetGenerationMapper.selectById(2L)).thenReturn(selected);
        when(assetGenerationMapper.selectList(any())).thenReturn(List.of());

        service.deleteGeneration(9L, 2L);

        assertNull(asset.getSelectedGenerationId());
        assertNull(asset.getImageStorageKey());
        assertNull(asset.getImageAssetId());
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
