package cn.aigc.dit.server.drama;

import cn.aigc.dit.common.constant.TaskStatus;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.security.LoginUser;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.server.drama.entity.DramaAsset;
import cn.aigc.dit.server.drama.entity.DramaChapter;
import cn.aigc.dit.server.drama.entity.DramaEpisode;
import cn.aigc.dit.server.drama.entity.DramaSeries;
import cn.aigc.dit.server.drama.entity.DramaShot;
import cn.aigc.dit.server.drama.mapper.DramaAssetMapper;
import cn.aigc.dit.server.drama.mapper.DramaChapterMapper;
import cn.aigc.dit.server.drama.mapper.DramaEpisodeMapper;
import cn.aigc.dit.server.drama.mapper.DramaSeriesMapper;
import cn.aigc.dit.server.drama.mapper.DramaShotMapper;
import cn.aigc.dit.server.drama.model.req.DramaShotCreateReq;
import cn.aigc.dit.server.drama.model.req.DramaShotSaveReq;
import cn.aigc.dit.server.drama.model.resp.DramaShotGenerateResp;
import cn.aigc.dit.server.drama.model.resp.DramaShotResp;
import cn.aigc.dit.server.drama.model.resp.DramaStoryboardResp;
import cn.aigc.dit.server.drama.text.DramaTextService;
import cn.aigc.dit.server.drama.text.ScriptDoc;
import cn.aigc.dit.server.drama.text.ScriptDocs;
import cn.aigc.dit.server.entity.AiModel;
import cn.aigc.dit.server.entity.GenTask;
import cn.aigc.dit.server.generate.GenerateRequests;
import cn.aigc.dit.server.mapper.AiModelMapper;
import cn.aigc.dit.server.mapper.GenTaskMapper;
import cn.aigc.dit.server.model.req.GenerateSubmitReq;
import cn.aigc.dit.server.model.resp.GenerateTaskResp;
import cn.aigc.dit.server.service.GenerateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DramaStoryboardServiceTest {

    @Mock
    private DramaSeriesMapper seriesMapper;
    @Mock
    private DramaEpisodeMapper episodeMapper;
    @Mock
    private DramaAssetMapper assetMapper;
    @Mock
    private DramaChapterMapper chapterMapper;
    @Mock
    private DramaShotMapper shotMapper;
    @Mock
    private AiModelMapper aiModelMapper;
    @Mock
    private GenTaskMapper genTaskMapper;
    @Mock
    private GenerateService generateService;
    @Mock
    private ObjectProvider<DramaTextService> textService;
    @Mock
    private DramaFilmService filmService;

    private DramaStoryboardService service;
    private final List<DramaChapter> storedChapters = new ArrayList<>();
    private final List<DramaShot> storedShots = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(100);

    @BeforeEach
    void setUp() {
        service = new DramaStoryboardService(
                seriesMapper, episodeMapper, assetMapper, chapterMapper, shotMapper,
                aiModelMapper, genTaskMapper, generateService, textService, filmService,
                new DramaStoryboardDraftLock(episodeMapper));
        UserContext.set(new LoginUser(7L, "creator", "c@example.com", "创作者", LoginUser.ROLE_CREATOR));
        storedChapters.clear();
        storedShots.clear();
        org.mockito.Mockito.lenient().when(textService.getIfAvailable()).thenReturn(null);
        org.mockito.Mockito.lenient().when(episodeMapper.updateById(any(DramaEpisode.class))).thenReturn(1);
        org.mockito.Mockito.lenient().when(chapterMapper.insert(any(DramaChapter.class))).thenAnswer(invocation -> {
            DramaChapter chapter = invocation.getArgument(0);
            chapter.setId(ids.incrementAndGet());
            storedChapters.add(chapter);
            return 1;
        });
        org.mockito.Mockito.lenient().when(shotMapper.insert(any(DramaShot.class))).thenAnswer(invocation -> {
            DramaShot shot = invocation.getArgument(0);
            shot.setId(ids.incrementAndGet());
            storedShots.add(shot);
            return 1;
        });
        org.mockito.Mockito.lenient().when(chapterMapper.selectList(any())).thenAnswer(invocation -> List.copyOf(storedChapters));
        org.mockito.Mockito.lenient().when(shotMapper.selectList(any())).thenAnswer(invocation -> List.copyOf(storedShots));
        org.mockito.Mockito.lenient().when(chapterMapper.selectById(any())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            return storedChapters.stream().filter(item -> id.equals(item.getId())).findFirst().orElse(null);
        });
        org.mockito.Mockito.lenient().when(shotMapper.selectById(any())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            return storedShots.stream().filter(item -> id.equals(item.getId())).findFirst().orElse(null);
        });
        org.mockito.Mockito.lenient().doAnswer(invocation -> {
            Long id = ((Number) invocation.getArgument(0)).longValue();
            boolean removed = storedShots.removeIf(item -> id.equals(item.getId()));
            return removed ? 1 : 0;
        }).when(shotMapper).deleteById(org.mockito.ArgumentMatchers.any(java.io.Serializable.class));
        org.mockito.Mockito.lenient().doNothing().when(filmService).discardAssembledFilm(org.mockito.ArgumentMatchers.anyLong());
        org.mockito.Mockito.lenient().when(seriesMapper.selectById(21L)).thenReturn(ownedSeries());
        org.mockito.Mockito.lenient().when(genTaskMapper.selectList(any())).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(genTaskMapper.selectBatchIds(any())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void draftRequiresLockedScript() {
        DramaEpisode episode = ownedEpisode();
        episode.setStatus(DramaContract.STATUS_PENDING_CONFIRM);
        when(episodeMapper.selectById(31L)).thenReturn(episode);
        BizException error = assertThrows(BizException.class, () -> service.draft(31L));
        assertEquals("请先锁定分场剧本再拆章节", error.getMessage());
    }

    @Test
    void draftBindsOnlyLockedAssetsAndCompilesPrefix() {
        DramaEpisode episode = ownedEpisode();
        episode.setStatus(DramaContract.STATUS_LOCKED);
        episode.setScript(ScriptDocs.toMap(detectiveDoc()));
        when(episodeMapper.selectById(31L)).thenReturn(episode);
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER),
                lockedAsset(2L, "夏安", DramaContract.ASSET_TYPE_CHARACTER),
                pendingAsset(3L, "局长", DramaContract.ASSET_TYPE_CHARACTER),
                lockedAsset(4L, "古铜怀表", DramaContract.ASSET_TYPE_PROP),
                lockedAsset(5L, "幻梦侦探社", DramaContract.ASSET_TYPE_SCENE)));

        DramaStoryboardResp board = service.draft(31L);

        assertTrue(storedShots.size() >= 4 && storedShots.size() <= 8);
        DramaShot first = storedShots.get(0);
        assertTrue(first.getCompiledPrompt().contains(DramaStyles.require(DramaStyles.ANIME_COMIC).prefix()));
        assertTrue(first.getCompiledPrompt().startsWith("<Picture 1>"));
        assertTrue(first.getReferenceAssetIds().contains(1L));
        assertEquals(2L, first.getReferenceAssetIds().get(0));
        assertFalse(first.getReferenceAssetIds().contains(3L));
        assertTrue(first.getCompiledPrompt().contains("<Picture 1>"));
        assertEquals(first.getCastCount(), first.getCharacterNames().size());
        assertEquals(1, board.getChapters().size());
        assertTrue(board.getChapters().get(0).getShots().size() >= 4);
        org.junit.jupiter.api.Assertions.assertNull(episode.getStoryboardDraftingAt());
        assertFalse(Boolean.TRUE.equals(board.getDrafting()));
    }

    @Test
    void getStoryboardReportsDraftingWhileLockIsHeld() {
        DramaEpisode episode = ownedEpisode();
        episode.setStoryboardDraftingAt(java.time.OffsetDateTime.now());
        when(episodeMapper.selectById(31L)).thenReturn(episode);

        DramaStoryboardResp board = service.getStoryboard(31L);

        assertTrue(Boolean.TRUE.equals(board.getDrafting()));
        assertEquals(episode.getStoryboardDraftingAt(), board.getDraftingAt());
    }

    @Test
    void getStoryboardIgnoresStaleDraftingLock() {
        DramaEpisode episode = ownedEpisode();
        episode.setStoryboardDraftingAt(java.time.OffsetDateTime.now().minusMinutes(11));
        when(episodeMapper.selectById(31L)).thenReturn(episode);

        DramaStoryboardResp board = service.getStoryboard(31L);

        assertFalse(Boolean.TRUE.equals(board.getDrafting()));
    }

    @Test
    void draftRejectsWhenAlreadyDrafting() {
        DramaEpisode episode = ownedEpisode();
        episode.setStatus(DramaContract.STATUS_LOCKED);
        episode.setScript(ScriptDocs.toMap(detectiveDoc()));
        episode.setStoryboardDraftingAt(java.time.OffsetDateTime.now());
        when(episodeMapper.selectById(31L)).thenReturn(episode);

        BizException error = assertThrows(BizException.class, () -> service.draft(31L));
        assertEquals(DramaStoryboardDraftLock.BUSY_MESSAGE, error.getMessage());
        assertTrue(storedShots.isEmpty());
    }

    @Test
    void updateShotRejectsWhenDrafting() {
        storedChapters.add(pendingChapter());
        storedShots.add(pendingShot());
        DramaEpisode episode = ownedEpisode();
        episode.setStoryboardDraftingAt(java.time.OffsetDateTime.now());
        when(episodeMapper.selectById(31L)).thenReturn(episode);
        DramaShotSaveReq req = new DramaShotSaveReq();
        req.setNarrativeGoal("中景。夏安往白板贴照片。");

        BizException error = assertThrows(BizException.class, () -> service.updateShot(201L, req));
        assertEquals(DramaStoryboardDraftLock.BUSY_MESSAGE, error.getMessage());
    }

    @Test
    void draftKeepsPerShotDurationsInsteadOfUniformOverride() {
        DramaEpisode episode = ownedEpisode();
        episode.setStatus(DramaContract.STATUS_LOCKED);
        episode.setScript(ScriptDocs.toMap(detectiveDoc()));
        when(episodeMapper.selectById(31L)).thenReturn(episode);
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER),
                lockedAsset(5L, "幻梦侦探社", DramaContract.ASSET_TYPE_SCENE)));

        service.draft(31L, true, 15);

        assertFalse(storedShots.isEmpty());
        assertTrue(storedShots.stream().allMatch(item ->
                item.getDurationSec() != null && item.getDurationSec() >= 5 && item.getDurationSec() <= 15));
        assertTrue(storedShots.stream().allMatch(item ->
                item.getCompiledPrompt().contains("Duration: " + item.getDurationSec() + " seconds")));
    }

    @Test
    void enqueueAutoDraftProducesShotsAndClearsLock() {
        DramaEpisode episode = ownedEpisode();
        episode.setStatus(DramaContract.STATUS_LOCKED);
        episode.setScript(ScriptDocs.toMap(detectiveDoc()));
        when(episodeMapper.selectById(31L)).thenReturn(episode);
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER),
                lockedAsset(5L, "幻梦侦探社", DramaContract.ASSET_TYPE_SCENE)));

        service.enqueueAutoDraft(31L, 30);

        assertFalse(storedShots.isEmpty());
        org.junit.jupiter.api.Assertions.assertNull(episode.getStoryboardDraftingAt());
    }

    @Test
    void confirmRejectsUnlockedNamedAssets() {
        DramaShot shot = pendingShot();
        storedShots.add(shot);
        storedChapters.add(pendingChapter());
        when(assetMapper.selectList(any())).thenReturn(List.of(
                pendingAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER)));

        BizException error = assertThrows(BizException.class, () -> service.confirmShot(201L));
        assertTrue(error.getMessage().contains("未锁定不能进分镜参考"));
    }

    @Test
    void pickNamedAssetPrefersLockedVariantOverUnlockedBase() {
        DramaAsset unlockedBase = pendingAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER);
        DramaAsset lockedCostume = lockedAsset(2L, "洛白", DramaContract.ASSET_TYPE_CHARACTER);
        lockedCostume.setStateTag(DramaContract.STATE_TAG_COSTUME);

        DramaAsset picked = DramaStoryboardService.pickNamedAsset(
                List.of(unlockedBase, lockedCostume),
                "洛白",
                DramaContract.ASSET_TYPE_CHARACTER,
                false);
        assertEquals(2L, picked.getId());
        assertEquals(DramaContract.STATUS_LOCKED, picked.getStatus());
    }

    @Test
    void storyboardDoesNotFlagUnboundWhenSameNameLockedExists() {
        when(episodeMapper.selectById(31L)).thenReturn(ownedEpisode());
        storedChapters.add(pendingChapter());
        DramaShot shot = pendingShot();
        shot.setCharacterNames(List.of("洛白"));
        storedShots.add(shot);
        DramaAsset unlockedBase = pendingAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER);
        DramaAsset lockedCostume = lockedAsset(2L, "洛白", DramaContract.ASSET_TYPE_CHARACTER);
        lockedCostume.setStateTag(DramaContract.STATE_TAG_COSTUME);
        when(assetMapper.selectList(any())).thenReturn(List.of(unlockedBase, lockedCostume));

        DramaStoryboardResp board = service.getStoryboard(31L);

        DramaShotResp item = board.getChapters().get(0).getShots().get(0);
        assertTrue(item.getUnboundNames() == null || item.getUnboundNames().isEmpty());
        assertEquals(1, item.getReferences().size());
        assertEquals(2L, item.getReferences().get(0).getAssetId());
        assertEquals(true, item.getReferences().get(0).getLocked());
        assertEquals(1, board.getLockedAssets().size());
    }

    @Test
    void identityChangeClearsCompiledPrompt() {
        DramaShot shot = pendingShot();
        shot.setStatus(DramaContract.STATUS_LOCKED);
        shot.setCompiledPrompt("旧prompt");
        shot.setCompiledFingerprint("old");
        shot.setReferenceAssetIds(List.of(1L));
        storedShots.add(shot);

        service.invalidateByAsset(31L, 1L);

        assertEquals(DramaContract.STATUS_EXPIRED, shot.getStatus());
        org.junit.jupiter.api.Assertions.assertNull(shot.getCompiledPrompt());
        ArgumentCaptor<DramaShot> captor = ArgumentCaptor.forClass(DramaShot.class);
        verify(shotMapper).updateById(captor.capture());
        assertEquals(DramaContract.STATUS_EXPIRED, captor.getValue().getStatus());
    }

    @Test
    void confirmSucceedsWhenAssetsLocked() {
        DramaChapter chapter = pendingChapter();
        storedChapters.add(chapter);
        DramaShot shot = pendingShot();
        shot.setChapterId(chapter.getId());
        storedShots.add(shot);
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER),
                lockedAsset(5L, "幻梦侦探社", DramaContract.ASSET_TYPE_SCENE)));

        DramaShotResp resp = service.confirmShot(201L);
        assertEquals(DramaContract.STATUS_LOCKED, resp.getStatus());
        assertTrue(resp.getCompiledPrompt().contains("<Picture"));
        assertFalse(Boolean.TRUE.equals(resp.getCompiledStale()));
        verify(shotMapper, org.mockito.Mockito.atLeastOnce()).updateById(any(DramaShot.class));
    }

    @Test
    void storyboardKeepsGeneratingAfterReloadWhenTaskStillPending() {
        when(episodeMapper.selectById(31L)).thenReturn(ownedEpisode());
        DramaChapter chapter = pendingChapter();
        storedChapters.add(chapter);
        DramaShot shot = pendingShot();
        shot.setShotIndex(3);
        shot.setGenTaskId(500L);
        storedShots.add(shot);
        when(assetMapper.selectList(any())).thenReturn(List.of());
        GenTask task = new GenTask();
        task.setId(500L);
        task.setStatus(TaskStatus.PENDING);
        when(genTaskMapper.selectBatchIds(any())).thenReturn(List.of(task));
        GenerateTaskResp pending = new GenerateTaskResp();
        pending.setId(500L);
        pending.setStatus(TaskStatus.PENDING);
        when(generateService.getMine(500L)).thenReturn(pending);

        DramaStoryboardResp board = service.getStoryboard(31L);

        DramaShotResp item = board.getChapters().get(0).getShots().get(0);
        assertTrue(Boolean.TRUE.equals(item.getGenerating()));
        assertEquals(TaskStatus.PENDING, item.getGenTaskStatus());
        assertEquals(500L, item.getGenTaskId());
    }

    @Test
    void storyboardRecoversInFlightTaskWhenShotLostGenTaskId() {
        when(episodeMapper.selectById(31L)).thenReturn(ownedEpisode());
        storedChapters.add(pendingChapter());
        DramaShot shot = pendingShot();
        shot.setShotIndex(3);
        shot.setGenTaskId(null);
        storedShots.add(shot);
        when(assetMapper.selectList(any())).thenReturn(List.of());
        GenTask live = new GenTask();
        live.setId(77L);
        live.setStatus(TaskStatus.RUNNING);
        live.setParams(java.util.Map.of(
                "source", GenerateRequests.SOURCE_DRAMA,
                "episodeId", 31L,
                "shotId", 201L));
        when(genTaskMapper.selectList(any())).thenReturn(List.of(live));

        DramaStoryboardResp board = service.getStoryboard(31L);

        DramaShotResp item = board.getChapters().get(0).getShots().get(0);
        assertTrue(Boolean.TRUE.equals(item.getGenerating()));
        assertEquals(TaskStatus.RUNNING, item.getGenTaskStatus());
        assertEquals(77L, item.getGenTaskId());
        assertEquals(77L, shot.getGenTaskId());
    }

    @Test
    void generateRequiresBoundVideoModel() {
        DramaShot shot = pendingShot();
        storedShots.add(shot);
        storedChapters.add(pendingChapter());

        BizException error = assertThrows(BizException.class, () -> service.generateShot(201L));
        assertEquals("请先在分镜页选择视频模型", error.getMessage());
        verify(generateService, never()).submit(any(GenerateSubmitReq.class), any());
    }

    @Test
    void generateSubmitsR2vWithLockedRefsAndNoFrames() {
        DramaChapter chapter = pendingChapter();
        storedChapters.add(chapter);
        DramaShot shot = pendingShot();
        shot.setChapterId(chapter.getId());
        storedShots.add(shot);
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER),
                lockedAsset(5L, "幻梦侦探社", DramaContract.ASSET_TYPE_SCENE)));
        DramaSeries series = ownedSeries();
        series.setVideoModelId(88L);
        series.setAspectRatio("16:9");
        series.setDefaultResolution("720P");
        when(seriesMapper.selectById(21L)).thenReturn(series);
        AiModel model = r2vModel();
        when(aiModelMapper.selectById(88L)).thenReturn(model);
        GenerateTaskResp task = new GenerateTaskResp();
        task.setId(500L);
        task.setStatus("PENDING");
        when(generateService.submit(any(GenerateSubmitReq.class), any())).thenReturn(task);

        DramaShotGenerateResp resp = service.generateShot(201L);

        ArgumentCaptor<GenerateSubmitReq> reqCaptor = ArgumentCaptor.forClass(GenerateSubmitReq.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> extraCaptor = ArgumentCaptor.forClass(java.util.Map.class);
        verify(generateService).submit(reqCaptor.capture(), extraCaptor.capture());
        GenerateSubmitReq req = reqCaptor.getValue();
        assertEquals(88L, req.getModelId());
        assertEquals("video", req.getCategory());
        assertEquals(5, req.getDurationSec());
        assertEquals("16:9", req.getRatio());
        assertTrue(req.getPrompt().contains("<Picture"));
        assertTrue(req.getReferences() != null && !req.getReferences().isEmpty());
        org.junit.jupiter.api.Assertions.assertNull(req.getFrames());
        org.junit.jupiter.api.Assertions.assertNull(req.getReferences().get(0).getAssetId());
        assertEquals("users/7/assets/1.png", req.getReferences().get(0).getStorageKey());
        assertEquals(GenerateRequests.SOURCE_DRAMA, extraCaptor.getValue().get("source"));
        assertEquals(201L, extraCaptor.getValue().get("shotId"));
        assertEquals(500L, shot.getGenTaskId());
        assertTrue(Boolean.TRUE.equals(resp.getShot().getGenerating()));
        assertEquals(500L, resp.getTask().getId());
        assertEquals(List.of(1L, 5L), shot.getReferenceAssetIds());
        assertEquals(2, resp.getShot().getReferences().size());
        assertEquals(1, resp.getShot().getReferences().get(0).getPictureIndex());
        assertEquals("洛白", resp.getShot().getReferences().get(0).getName());
        assertEquals("幻梦侦探社", resp.getShot().getReferences().get(1).getName());
    }

    @Test
    void generateOnOfficialSeedanceKeepsCharacterAndSceneImages() {
        DramaChapter chapter = pendingChapter();
        storedChapters.add(chapter);
        DramaShot shot = pendingShot();
        shot.setChapterId(chapter.getId());
        storedShots.add(shot);
        shot.setPropNames(List.of("古铜怀表"));
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER),
                lockedAsset(5L, "幻梦侦探社", DramaContract.ASSET_TYPE_SCENE),
                lockedAsset(8L, "古铜怀表", DramaContract.ASSET_TYPE_PROP)));
        DramaSeries series = ownedSeries();
        series.setVideoModelId(88L);
        series.setAspectRatio("16:9");
        series.setStylePrefix("二次元漫画风，干净线稿，赛璐璐平涂，清晰分色，电影分镜构图，不要写实摄影质感，不要真实人脸，不要真人照片");
        when(seriesMapper.selectById(21L)).thenReturn(series);
        when(aiModelMapper.selectById(88L)).thenReturn(seedanceModel());
        GenerateTaskResp task = new GenerateTaskResp();
        task.setId(500L);
        task.setStatus("PENDING");
        when(generateService.submit(any(GenerateSubmitReq.class), any())).thenReturn(task);

        service.generateShot(201L);

        ArgumentCaptor<GenerateSubmitReq> reqCaptor = ArgumentCaptor.forClass(GenerateSubmitReq.class);
        verify(generateService).submit(reqCaptor.capture(), any());
        GenerateSubmitReq req = reqCaptor.getValue();
        assertEquals(3, req.getReferences().size());
        assertEquals("users/7/assets/5.png", req.getReferences().get(0).getStorageKey());
        assertEquals("users/7/assets/8.png", req.getReferences().get(1).getStorageKey());
        assertEquals("users/7/assets/1.png", req.getReferences().get(2).getStorageKey());
        assertTrue(req.getPrompt().contains("<Picture 1> 幻梦侦探社"));
        assertTrue(req.getPrompt().contains("<Picture 2> 古铜怀表"));
        assertTrue(req.getPrompt().contains("<Picture 3> 洛白"));
        assertFalse(req.getPrompt().contains("真实人脸"));
        assertFalse(req.getPrompt().contains("真人照片"));
        assertFalse(req.getPrompt().contains("real people"));
        assertEquals(List.of(1L, 8L, 5L), shot.getReferenceAssetIds());
    }

    @Test
    void generateDoesNotReuseInFlightTaskFromAnotherModel() {
        DramaChapter chapter = pendingChapter();
        storedChapters.add(chapter);
        DramaShot shot = pendingShot();
        shot.setChapterId(chapter.getId());
        shot.setGenTaskId(400L);
        storedShots.add(shot);
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER),
                lockedAsset(5L, "幻梦侦探社", DramaContract.ASSET_TYPE_SCENE)));
        DramaSeries series = ownedSeries();
        series.setVideoModelId(88L);
        when(seriesMapper.selectById(21L)).thenReturn(series);
        when(aiModelMapper.selectById(88L)).thenReturn(r2vModel());
        GenTask stale = new GenTask();
        stale.setId(400L);
        stale.setModelId(7L);
        stale.setStatus(TaskStatus.RUNNING);
        stale.setErrorMessage("Agnes 查询 HTTP 404");
        when(genTaskMapper.selectById(400L)).thenReturn(stale);
        GenerateTaskResp fresh = new GenerateTaskResp();
        fresh.setId(502L);
        fresh.setModelId(88L);
        fresh.setStatus("PENDING");
        when(generateService.submit(any(GenerateSubmitReq.class), any())).thenReturn(fresh);

        DramaShotGenerateResp resp = service.generateShot(201L);

        verify(generateService).submit(any(GenerateSubmitReq.class), any());
        assertEquals(TaskStatus.CANCELLED, stale.getStatus());
        assertEquals(502L, shot.getGenTaskId());
        assertEquals(502L, resp.getTask().getId());
    }

    @Test
    void generateAbandonsInFlightTaskAndSubmitsNew() {
        DramaChapter chapter = pendingChapter();
        storedChapters.add(chapter);
        DramaShot shot = pendingShot();
        shot.setChapterId(chapter.getId());
        shot.setGenTaskId(400L);
        storedShots.add(shot);
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER),
                lockedAsset(5L, "幻梦侦探社", DramaContract.ASSET_TYPE_SCENE)));
        DramaSeries series = ownedSeries();
        series.setVideoModelId(88L);
        when(seriesMapper.selectById(21L)).thenReturn(series);
        when(aiModelMapper.selectById(88L)).thenReturn(r2vModel());
        GenTask existing = new GenTask();
        existing.setId(400L);
        existing.setModelId(88L);
        existing.setStatus(TaskStatus.RUNNING);
        when(genTaskMapper.selectById(400L)).thenReturn(existing);
        GenerateTaskResp fresh = new GenerateTaskResp();
        fresh.setId(503L);
        fresh.setModelId(88L);
        fresh.setStatus("PENDING");
        when(generateService.submit(any(GenerateSubmitReq.class), any())).thenReturn(fresh);

        DramaShotGenerateResp resp = service.generateShot(201L);

        verify(generateService).submit(any(GenerateSubmitReq.class), any());
        assertEquals(TaskStatus.CANCELLED, existing.getStatus());
        assertEquals(503L, resp.getTask().getId());
        assertEquals(503L, shot.getGenTaskId());
    }

    @Test
    void generateRebindsNamedPicturesEvenIfManualRefsStale() {
        DramaChapter chapter = pendingChapter();
        storedChapters.add(chapter);
        DramaShot shot = pendingShot();
        shot.setChapterId(chapter.getId());
        shot.setReferenceAssetIds(List.of(5L));
        storedShots.add(shot);
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER),
                lockedAsset(5L, "幻梦侦探社", DramaContract.ASSET_TYPE_SCENE)));
        DramaSeries series = ownedSeries();
        series.setVideoModelId(88L);
        when(seriesMapper.selectById(21L)).thenReturn(series);
        when(aiModelMapper.selectById(88L)).thenReturn(r2vModel());
        GenerateTaskResp task = new GenerateTaskResp();
        task.setId(501L);
        task.setStatus("PENDING");
        when(generateService.submit(any(GenerateSubmitReq.class), any())).thenReturn(task);

        service.generateShot(201L);

        ArgumentCaptor<GenerateSubmitReq> reqCaptor = ArgumentCaptor.forClass(GenerateSubmitReq.class);
        verify(generateService).submit(reqCaptor.capture(), any());
        assertEquals(2, reqCaptor.getValue().getReferences().size());
        assertEquals("users/7/assets/1.png", reqCaptor.getValue().getReferences().get(0).getStorageKey());
        assertEquals("users/7/assets/5.png", reqCaptor.getValue().getReferences().get(1).getStorageKey());
        assertEquals(List.of(1L, 5L), shot.getReferenceAssetIds());
    }

    @Test
    void generateRejectsUnlockedNamedAssets() {
        DramaShot shot = pendingShot();
        storedShots.add(shot);
        storedChapters.add(pendingChapter());
        DramaSeries series = ownedSeries();
        series.setVideoModelId(88L);
        when(seriesMapper.selectById(21L)).thenReturn(series);
        when(aiModelMapper.selectById(88L)).thenReturn(r2vModel());
        when(assetMapper.selectList(any())).thenReturn(List.of(
                pendingAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER)));

        BizException error = assertThrows(BizException.class, () -> service.generateShot(201L));
        assertTrue(error.getMessage().contains("尚未锁定") || error.getMessage().contains("请先锁定资产参考图"));
        verify(generateService, never()).submit(any(GenerateSubmitReq.class), any());
    }

    @Test
    void getGenerateTaskWritesVideoKey() {
        DramaShot shot = pendingShot();
        shot.setGenTaskId(500L);
        storedShots.add(shot);
        storedChapters.add(pendingChapter());
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER)));
        GenerateTaskResp task = new GenerateTaskResp();
        task.setId(500L);
        task.setStatus("SUCCEEDED");
        task.setOutputStorageKey("users/7/gen/shot.mp4");
        task.setOutputKeys(List.of("users/7/gen/shot.mp4"));
        task.setPosterKey("users/7/gen/shot-poster.webp");
        when(generateService.getMine(500L)).thenReturn(task);

        DramaShotGenerateResp resp = service.getGenerateTask(201L, 500L);
        assertEquals("users/7/gen/shot.mp4", shot.getVideoKey());
        assertEquals("users/7/gen/shot-poster.webp", shot.getVideoPosterKey());
        assertEquals("/api/media?key=users%2F7%2Fgen%2Fshot.mp4", resp.getShot().getVideoUrl());
        assertEquals("/api/media?key=users%2F7%2Fgen%2Fshot-poster.webp", resp.getShot().getVideoPosterUrl());
        assertFalse(Boolean.TRUE.equals(resp.getShot().getGenerating()));
    }

    @Test
    void createShotAppendsToLastChapterAndInheritsCast() {
        when(episodeMapper.selectById(31L)).thenReturn(ownedEpisode());
        when(assetMapper.selectList(any())).thenReturn(List.of());
        storedChapters.add(pendingChapter());
        storedShots.add(pendingShot());

        var resp = service.createShot(31L, null);

        assertEquals(2, storedShots.size());
        DramaShot created = storedShots.get(1);
        assertEquals(2, created.getShotIndex());
        assertEquals(90L, created.getChapterId());
        assertEquals("分镜 2", created.getTitle());
        assertEquals(5, created.getDurationSec());
        assertEquals(DramaContract.STATUS_PENDING_CONFIRM, created.getStatus());
        assertEquals(List.of("洛白"), created.getCharacterNames());
        assertEquals(1, created.getCastCount());
        assertEquals("幻梦侦探社", created.getLocationName());
        assertEquals(resp.getId(), created.getId());
    }

    @Test
    void createShotInsertsAfterGivenShotAndRenumbers() {
        when(episodeMapper.selectById(31L)).thenReturn(ownedEpisode());
        when(assetMapper.selectList(any())).thenReturn(List.of());
        storedChapters.add(pendingChapter());
        for (int i = 1; i <= 3; i++) {
            DramaShot shot = pendingShot();
            shot.setId(200L + i);
            shot.setShotIndex(i);
            shot.setTitle("镜 " + i);
            storedShots.add(shot);
        }
        DramaShotCreateReq req = new DramaShotCreateReq();
        req.setAfterShotId(201L);

        DramaShotResp resp = service.createShot(31L, req);

        assertEquals(Integer.valueOf(2), resp.getShotIndex());
        assertEquals(4, storedShots.size());
        assertEquals(1, storedShots.stream().filter(item -> item.getId() == 201L).findFirst().orElseThrow().getShotIndex());
        assertEquals(3, storedShots.stream().filter(item -> item.getId() == 202L).findFirst().orElseThrow().getShotIndex());
        assertEquals(4, storedShots.stream().filter(item -> item.getId() == 203L).findFirst().orElseThrow().getShotIndex());
        assertEquals(List.of("洛白"), resp.getCharacterNames());
    }

    @Test
    void createShotRejectsWhenChapterIsFull() {
        when(episodeMapper.selectById(31L)).thenReturn(ownedEpisode());
        storedChapters.add(pendingChapter());
        for (int i = 1; i <= DramaContract.MAX_SHOTS_PER_CHAPTER; i++) {
            DramaShot shot = pendingShot();
            shot.setId(200L + i);
            shot.setShotIndex(i);
            storedShots.add(shot);
        }

        BizException error = assertThrows(BizException.class, () -> service.createShot(31L, null));
        assertTrue(error.getMessage().contains("单场最多"));
    }

    @Test
    void deleteShotRemovesAndRenumbersRemaining() {
        when(episodeMapper.selectById(31L)).thenReturn(ownedEpisode());
        when(assetMapper.selectList(any())).thenReturn(List.of());
        storedChapters.add(pendingChapter());
        for (int i = 1; i <= 3; i++) {
            DramaShot shot = pendingShot();
            shot.setId(200L + i);
            shot.setShotIndex(i);
            shot.setTitle("镜 " + i);
            storedShots.add(shot);
        }

        DramaStoryboardResp board = service.deleteShot(201L);

        assertNotNull(board);
        List<Integer> indexes = storedShots.stream()
                .filter(item -> !Long.valueOf(201L).equals(item.getId()))
                .map(DramaShot::getShotIndex)
                .toList();
        assertEquals(List.of(1, 2), indexes);
        verify(filmService).discardAssembledFilm(31L);
    }

    @Test
    void deleteShotAllowsGeneratedVideoAndDiscardsFilm() {
        when(episodeMapper.selectById(31L)).thenReturn(ownedEpisode());
        when(assetMapper.selectList(any())).thenReturn(List.of());
        storedChapters.add(pendingChapter());
        DramaShot generated = pendingShot();
        generated.setId(201L);
        generated.setShotIndex(1);
        generated.setVideoKey("users/7/videos/1.mp4");
        storedShots.add(generated);
        DramaShot other = pendingShot();
        other.setId(202L);
        other.setShotIndex(2);
        storedShots.add(other);

        DramaStoryboardResp board = service.deleteShot(201L);

        assertNotNull(board);
        assertEquals(1, storedShots.size());
        assertEquals(202L, storedShots.get(0).getId());
        assertEquals(1, storedShots.get(0).getShotIndex());
        verify(filmService).discardAssembledFilm(31L);
    }

    @Test
    void deleteShotRejectsConfirmedAndLast() {
        storedChapters.add(pendingChapter());

        DramaShot confirmed = pendingShot();
        confirmed.setStatus(DramaContract.STATUS_LOCKED);
        storedShots.add(confirmed);
        assertEquals("已确认分镜不能删除",
                assertThrows(BizException.class, () -> service.deleteShot(201L)).getMessage());
        storedShots.clear();

        storedShots.add(pendingShot());
        assertEquals("至少保留一个分镜",
                assertThrows(BizException.class, () -> service.deleteShot(201L)).getMessage());
        verify(filmService, never()).discardAssembledFilm(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void updateShotRecompilesActionFromNarrativeGoal() {
        DramaChapter chapter = pendingChapter();
        storedChapters.add(chapter);
        DramaShot shot = pendingShot();
        shot.setChapterId(chapter.getId());
        shot.setCompiledPrompt("旧prompt");
        storedShots.add(shot);
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER)));

        DramaShotSaveReq req = new DramaShotSaveReq();
        req.setNarrativeGoal("中景。夏安往白板贴照片。");
        req.setScene("幻梦侦探社 日 内");
        DramaShotResp resp = service.updateShot(201L, req);

        assertEquals("中景。夏安往白板贴照片。", resp.getNarrativeGoal());
        assertEquals("幻梦侦探社 日 内", resp.getScene());
        assertTrue(resp.getCompiledPrompt().contains("Action: 中景。夏安往白板贴照片。"));
        assertTrue(resp.getCompiledPrompt().contains("Scene: 幻梦侦探社 日 内"));
        assertFalse(resp.getCompiledPrompt().contains("交代空间"));
    }

    @Test
    void updateShotKeepsAddedCharacterReferenceEvenIfActionOmitsName() {
        DramaChapter chapter = pendingChapter();
        storedChapters.add(chapter);
        DramaShot shot = pendingShot();
        shot.setChapterId(chapter.getId());
        shot.setNarrativeGoal("特写。洛白在桌后装睡。");
        shot.setCharacterNames(List.of("洛白"));
        storedShots.add(shot);
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER),
                lockedAsset(2L, "夏安", DramaContract.ASSET_TYPE_CHARACTER)));

        DramaShotSaveReq req = new DramaShotSaveReq();
        req.setCharacterNames(List.of("洛白", "夏安"));
        DramaShotResp resp = service.updateShot(201L, req);

        assertEquals(List.of("洛白", "夏安"), resp.getCharacterNames());
        assertEquals(List.of(1L, 2L), resp.getReferenceAssetIds());
        assertEquals("洛白", resp.getReferences().get(0).getName());
        assertEquals("夏安", resp.getReferences().get(1).getName());
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
        series.setStyleKey(DramaStyles.ANIME_COMIC);
        series.setStylePrefix(DramaStyles.require(DramaStyles.ANIME_COMIC).prefix());
        series.setDefaultDurationSec(5);
        return series;
    }

    private static DramaAsset lockedAsset(Long id, String name, String type) {
        DramaAsset asset = pendingAsset(id, name, type);
        asset.setStatus(DramaContract.STATUS_LOCKED);
        asset.setIdentityVersion(1);
        asset.setImageStorageKey("users/7/assets/" + id + ".png");
        return asset;
    }

    private static DramaAsset pendingAsset(Long id, String name, String type) {
        DramaAsset asset = new DramaAsset();
        asset.setId(id);
        asset.setUserId(7L);
        asset.setSeriesId(21L);
        asset.setEpisodeId(31L);
        asset.setName(name);
        asset.setType(type);
        asset.setStateTag(DramaContract.STATE_TAG_BASE);
        asset.setStatus(DramaContract.STATUS_PENDING_CONFIRM);
        asset.setIdentityVersion(0);
        asset.setDescription(name + "完整描述永不缩写");
        asset.setIdentityInvariants(name + "身份不变量");
        return asset;
    }

    private static DramaChapter pendingChapter() {
        DramaChapter chapter = new DramaChapter();
        chapter.setId(90L);
        chapter.setEpisodeId(31L);
        chapter.setSeriesId(21L);
        chapter.setUserId(7L);
        chapter.setChapterIndex(1);
        chapter.setSceneIndex(1);
        chapter.setName("第一场");
        chapter.setStatus(DramaContract.STATUS_PENDING_CONFIRM);
        return chapter;
    }

    private static DramaShot pendingShot() {
        DramaShot shot = new DramaShot();
        shot.setId(201L);
        shot.setUserId(7L);
        shot.setSeriesId(21L);
        shot.setEpisodeId(31L);
        shot.setChapterId(90L);
        shot.setShotIndex(1);
        shot.setTitle("侦探社开场");
        shot.setDurationSec(5);
        shot.setScene("幻梦侦探社");
        shot.setNarrativeGoal("交代空间");
        shot.setCharacterNames(List.of("洛白"));
        shot.setPropNames(List.of());
        shot.setLocationName("幻梦侦探社");
        shot.setCastCount(1);
        shot.setStatus(DramaContract.STATUS_PENDING_CONFIRM);
        shot.setReferenceAssetIds(new ArrayList<>());
        return shot;
    }

    private static ScriptDoc detectiveDoc() {
        ScriptDoc doc = new ScriptDoc();
        ScriptDoc.Scene scene = new ScriptDoc.Scene();
        scene.setIndex(1);
        scene.setHeading("幻梦侦探社 日 内");
        scene.setCharacters(List.of("洛白", "夏安", "局长"));
        scene.setProps(List.of("古铜怀表"));
        scene.setLocation("幻梦侦探社");
        scene.setAction("风扇转动。夏安在白板贴照片。洛白用书盖脸。局长摔档案。洛白掏怀表。");
        ScriptDoc.Dialogue line = new ScriptDoc.Dialogue();
        line.setSpeaker("局长");
        line.setLine("洛老弟，别睡了。");
        scene.setDialogue(List.of(line));
        doc.setScenes(List.of(scene));
        return doc;
    }

    private static AiModel r2vModel() {
        AiModel model = new AiModel();
        model.setId(88L);
        model.setName("MiniMax-H3");
        model.setCategory("video");
        model.setEnabled(true);
        model.setAdapterKey("minimax-h3");
        model.setProvider("ComfyUI");
        model.setModelKey("minimax-h3");
        model.setCapabilities(List.of("video", "t2v", "r2v", "i2v"));
        return model;
    }

    private static AiModel seedanceModel() {
        AiModel model = new AiModel();
        model.setId(88L);
        model.setName("Seedance 2.0 Fast");
        model.setCategory("video");
        model.setEnabled(true);
        model.setAdapterKey("seedance-2.0");
        model.setProvider("官方API");
        model.setModelKey("doubao-seedance-2.0-fast");
        model.setCapabilities(List.of("video", "t2v", "r2v"));
        return model;
    }
}
