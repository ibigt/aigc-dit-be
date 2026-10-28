package cn.aigc.dit.server.drama;

import cn.aigc.dit.common.constant.TaskStatus;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.security.LoginUser;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.generate.GenerateMode;
import cn.aigc.dit.generate.poster.PosterExtractor;
import cn.aigc.dit.server.drama.entity.DramaAsset;
import cn.aigc.dit.server.drama.entity.DramaChapter;
import cn.aigc.dit.server.drama.entity.DramaEpisode;
import cn.aigc.dit.server.drama.entity.DramaSeries;
import cn.aigc.dit.server.drama.entity.DramaShot;
import cn.aigc.dit.server.drama.mapper.DramaAssetMapper;
import cn.aigc.dit.server.drama.mapper.DramaChapterMapper;
import cn.aigc.dit.server.drama.mapper.DramaEpisodeMapper;
import cn.aigc.dit.server.drama.mapper.DramaSeriesMapper;
import cn.aigc.dit.server.drama.mapper.DramaShotGenerationMapper;
import cn.aigc.dit.server.drama.mapper.DramaShotMapper;
import cn.aigc.dit.server.drama.entity.DramaShotGeneration;
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
import cn.aigc.dit.server.service.AssetService;
import cn.aigc.dit.server.service.CreationService;
import cn.aigc.dit.server.service.MediaRetentionService;
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
import static org.junit.jupiter.api.Assertions.assertNull;
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
    private DramaShotGenerationMapper shotGenerationMapper;
    @Mock
    private AiModelMapper aiModelMapper;
    @Mock
    private GenTaskMapper genTaskMapper;
    @Mock
    private GenerateService generateService;
    @Mock
    private AssetService assetService;
    @Mock
    private PosterExtractor posterExtractor;
    @Mock
    private ObjectProvider<DramaTextService> textService;
    @Mock
    private DramaFilmService filmService;
    @Mock
    private CreationService creationService;
    @Mock
    private MediaRetentionService mediaRetention;

    private DramaStoryboardService service;
    private final List<DramaChapter> storedChapters = new ArrayList<>();
    private final List<DramaShot> storedShots = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(100);

    @BeforeEach
    void setUp() {
        service = new DramaStoryboardService(
                seriesMapper, episodeMapper, assetMapper, chapterMapper, shotMapper, shotGenerationMapper,
                aiModelMapper, genTaskMapper, generateService, assetService, posterExtractor, textService, filmService,
                new DramaStoryboardDraftLock(episodeMapper),
                new DramaPromptRecompileLock(),
                creationService, mediaRetention);
        UserContext.set(new LoginUser(7L, "creator", "c@example.com", "创作者", LoginUser.ROLE_CREATOR));
        storedChapters.clear();
        storedShots.clear();
        org.mockito.Mockito.lenient().when(textService.getIfAvailable()).thenReturn(null);
        org.mockito.Mockito.lenient().when(episodeMapper.updateById(any(DramaEpisode.class))).thenReturn(1);
        org.mockito.Mockito.lenient().when(episodeMapper.claimStoryboardDraft(any(), any(), any())).thenReturn(1);
        org.mockito.Mockito.lenient().when(shotGenerationMapper.insert(any(DramaShotGeneration.class))).thenAnswer(invocation -> {
            DramaShotGeneration generation = invocation.getArgument(0);
            generation.setId(ids.incrementAndGet());
            return 1;
        });
        org.mockito.Mockito.lenient().when(shotGenerationMapper.selectCount(any())).thenReturn(0L);
        org.mockito.Mockito.lenient().when(chapterMapper.insert(any(DramaChapter.class))).thenAnswer(invocation -> {
            DramaChapter chapter = invocation.getArgument(0);
            chapter.setId(ids.incrementAndGet());
            storedChapters.add(chapter);
            return 1;
        });
        org.mockito.Mockito.lenient().when(shotMapper.updateById(any(DramaShot.class))).thenReturn(1);
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
        stubLlmDrafts();

        DramaStoryboardResp board = service.draft(31L);

        assertTrue(storedShots.size() >= 4 && storedShots.size() <= 8);
        DramaShot first = storedShots.get(0);
        assertTrue(first.getCompiledPrompt().contains(DramaStyles.require(DramaStyles.ANIME_COMIC).prefix()));
        assertTrue(first.getCompiledPrompt().startsWith("<Picture 1>"));
        assertTrue(first.getReferenceAssetIds().contains(1L));
        assertEquals(5L, first.getReferenceAssetIds().get(0));
        assertFalse(first.getReferenceAssetIds().contains(3L));
        assertTrue(first.getCompiledPrompt().contains("<Picture 1>"));
        assertEquals(first.getCastCount(), first.getCharacterNames().size());
        assertEquals(1, board.getChapters().size());
        assertTrue(board.getChapters().get(0).getShots().size() >= 4);
        DramaShotResp drafted = board.getChapters().get(0).getShots().get(0);
        assertNotNull(drafted.getCompiledPrompt());
        assertFalse(drafted.getCompiledPrompt().isBlank());
        assertFalse(Boolean.TRUE.equals(drafted.getCompiledStale()));
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
    void storyboardKeepsR2vWhenWan3SupportsReferenceVideo() {
        when(episodeMapper.selectById(31L)).thenReturn(ownedEpisode());
        storedChapters.add(pendingChapter());
        storedShots.add(pendingShot());
        when(assetMapper.selectList(any())).thenReturn(List.of());
        DramaSeries series = ownedSeries();
        series.setVideoGenerateMode(DramaVideoGenerateModes.R2V);
        series.setVideoModelId(88L);
        when(seriesMapper.selectById(21L)).thenReturn(series);
        when(aiModelMapper.selectById(88L)).thenReturn(wan3Model());

        DramaStoryboardResp board = service.getStoryboard(31L);

        assertEquals(DramaVideoGenerateModes.R2V,
                board.getChapters().get(0).getShots().get(0).getEffectiveVideoGenerateMode());
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
        stubLlmDrafts();

        service.draft(31L, true, 15);

        assertFalse(storedShots.isEmpty());
        assertTrue(storedShots.stream().allMatch(item ->
                item.getDurationSec() != null && item.getDurationSec() >= 5 && item.getDurationSec() <= 15));
        assertTrue(storedShots.stream().allMatch(item ->
                item.getCompiledPrompt().contains("Duration: " + item.getDurationSec() + " seconds")));
    }

    @Test
    void llmStoryboardDraftDoesNotFallBackWhenTextServiceIsMissing() {
        DramaEpisode episode = ownedEpisode();
        episode.setStatus(DramaContract.STATUS_LOCKED);
        episode.setScript(ScriptDocs.toMap(detectiveDoc()));
        when(episodeMapper.selectById(31L)).thenReturn(episode);
        when(assetMapper.selectList(any())).thenReturn(List.of());

        BizException error = assertThrows(BizException.class,
                () -> service.draft(31L, true, 15));

        assertEquals("剧本生成服务尚未就绪", error.getMessage());
        assertTrue(storedShots.isEmpty());
        verify(episodeMapper).failStoryboardDraft(org.mockito.ArgumentMatchers.eq(31L), any());
    }

    @Test
    void getStoryboardReportsDraftErrorWhenIdle() {
        DramaEpisode episode = ownedEpisode();
        episode.setStoryboardDraftError("文本模型超时");
        when(episodeMapper.selectById(31L)).thenReturn(episode);

        DramaStoryboardResp board = service.getStoryboard(31L);

        assertEquals("文本模型超时", board.getDraftError());
        assertEquals(DramaContract.STATUS_NOT_STARTED, board.getStatus());
    }

    @Test
    void draftWithoutLlmDoesNotInventRuleShots() {
        DramaEpisode episode = ownedEpisode();
        episode.setStatus(DramaContract.STATUS_LOCKED);
        episode.setScript(ScriptDocs.toMap(detectiveDoc()));
        when(episodeMapper.selectById(31L)).thenReturn(episode);
        when(assetMapper.selectList(any())).thenReturn(List.of());

        BizException error = assertThrows(BizException.class, () -> service.draft(31L, false));

        assertEquals("剧本生成服务尚未就绪", error.getMessage());
        assertTrue(storedShots.isEmpty());
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
        DramaTextService llm = org.mockito.Mockito.mock(DramaTextService.class);
        when(textService.getIfAvailable()).thenReturn(llm);
        when(llm.draftShots(org.mockito.ArgumentMatchers.anyLong(), any(ScriptDoc.class), any(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any()))
                .thenReturn(DramaShotDrafts.fromScript(
                        detectiveDoc(), List.of("洛白", "幻梦侦探社"), 5));

        service.enqueueAutoDraft(31L, 30);

        assertFalse(storedShots.isEmpty());
        org.junit.jupiter.api.Assertions.assertNull(episode.getStoryboardDraftingAt());
    }

    @Test
    void startDraftProducesShotsAndClearsLock() {
        DramaEpisode episode = ownedEpisode();
        episode.setStatus(DramaContract.STATUS_LOCKED);
        episode.setScript(ScriptDocs.toMap(detectiveDoc()));
        when(episodeMapper.selectById(31L)).thenReturn(episode);
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER),
                lockedAsset(5L, "幻梦侦探社", DramaContract.ASSET_TYPE_SCENE)));
        stubLlmDrafts();

        DramaStoryboardResp board = service.startDraft(31L, 15);

        assertFalse(storedShots.isEmpty());
        assertNotNull(board);
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
        assertTrue(error.getMessage().contains("还没有参考图"));
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
    void generateWarnsOnlyWhenRoundtripOriginSidecarExpired() {
        DramaChapter chapter = pendingChapter();
        storedChapters.add(chapter);
        DramaShot shot = pendingShot();
        shot.setChapterId(chapter.getId());
        storedShots.add(shot);
        DramaAsset withExpired = lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER);
        withExpired.setImageStorageKey("users/7/gen/roundtrip.png");
        DramaAsset externalNoSidecar = lockedAsset(5L, "幻梦侦探社", DramaContract.ASSET_TYPE_SCENE);
        externalNoSidecar.setImageStorageKey("users/7/upload/scene.jpg");
        when(assetMapper.selectList(any())).thenReturn(List.of(withExpired, externalNoSidecar));
        DramaSeries series = ownedSeries();
        series.setVideoModelId(88L);
        series.setAspectRatio("16:9");
        series.setDefaultResolution("720P");
        when(seriesMapper.selectById(21L)).thenReturn(series);
        when(aiModelMapper.selectById(88L)).thenReturn(r2vModel());
        GenerateTaskResp task = new GenerateTaskResp();
        task.setId(500L);
        task.setStatus("PENDING");
        when(generateService.submit(any(GenerateSubmitReq.class), any())).thenReturn(task);
        cn.aigc.dit.storage.ObjectStorage storage = org.mockito.Mockito.mock(cn.aigc.dit.storage.ObjectStorage.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "objectStorage", storage);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "objectMapper", new com.fasterxml.jackson.databind.ObjectMapper());
        when(storage.exists("users/7/gen/roundtrip.png.origin.json")).thenReturn(true);
        when(storage.exists("users/7/upload/scene.jpg.origin.json")).thenReturn(false);
        String expiredSidecar = """
                {"url":"https://ark.cn-beijing.volces.com/x","savedAt":"2020-01-01T00:00:00Z"}
                """;
        when(storage.getBytes("users/7/gen/roundtrip.png.origin.json"))
                .thenReturn(expiredSidecar.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        DramaShotGenerateResp resp = service.generateShot(201L);

        assertEquals(1, resp.getOriginWarnings().size());
        assertTrue(resp.getOriginWarnings().get(0).contains("洛白"));
        assertTrue(resp.getOriginWarnings().get(0).contains("origin"));
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
        assertEquals("users/7/assets/5.png", req.getReferences().get(0).getStorageKey());
        assertEquals(GenerateRequests.SOURCE_DRAMA, extraCaptor.getValue().get("source"));
        assertEquals(201L, extraCaptor.getValue().get("shotId"));
        assertEquals(500L, shot.getGenTaskId());
        assertTrue(Boolean.TRUE.equals(resp.getShot().getGenerating()));
        assertEquals(500L, resp.getTask().getId());
        assertEquals(List.of(5L, 1L), shot.getReferenceAssetIds());
        assertEquals(2, resp.getShot().getReferences().size());
        assertEquals(1, resp.getShot().getReferences().get(0).getPictureIndex());
        assertEquals("幻梦侦探社", resp.getShot().getReferences().get(0).getName());
        assertEquals("洛白", resp.getShot().getReferences().get(1).getName());
    }

    @Test
    void generateI2vUsesPrevTailAsFirstFrameAndDropsSheets() {
        DramaChapter chapter = pendingChapter();
        storedChapters.add(chapter);
        DramaShot first = pendingShot();
        first.setId(200L);
        first.setShotIndex(1);
        first.setChapterId(chapter.getId());
        first.setVideoTailKey("users/7/gen/shot1-tail.webp");
        storedShots.add(first);
        DramaShot second = pendingShot();
        second.setId(201L);
        second.setShotIndex(2);
        second.setChapterId(chapter.getId());
        storedShots.add(asI2v(second));
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER),
                lockedAsset(5L, "幻梦侦探社", DramaContract.ASSET_TYPE_SCENE)));
        DramaSeries series = ownedSeries();
        series.setVideoModelId(88L);
        series.setVideoGenerateMode(DramaVideoGenerateModes.I2V);
        series.setAspectRatio("16:9");
        when(seriesMapper.selectById(21L)).thenReturn(series);
        when(aiModelMapper.selectById(88L)).thenReturn(seedanceModel());

        BizException error = assertThrows(BizException.class, () -> service.generateShot(201L));
        assertTrue(error.getMessage().contains("首帧"));
        verify(generateService, never()).submit(any(GenerateSubmitReq.class), any());
}

    @Test
    void generateI2vIncomingCharacterStartsOpeningStill() {
        DramaChapter chapter = pendingChapter();
        storedChapters.add(chapter);
        DramaShot first = pendingShot();
        first.setId(200L);
        first.setShotIndex(1);
        first.setChapterId(chapter.getId());
        first.setVideoTailKey("users/7/gen/shot1-tail.webp");
        storedShots.add(first);
        DramaShot second = pendingShot();
        second.setId(201L);
        second.setShotIndex(2);
        second.setChapterId(chapter.getId());
        second.setNarrativeGoal("中景。局长推门将档案摔桌，洛白起身。");
        second.setCharacterNames(List.of("局长", "洛白"));
        storedShots.add(asI2v(second));
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER),
                lockedAsset(3L, "局长", DramaContract.ASSET_TYPE_CHARACTER)));
        DramaSeries series = ownedSeries();
        series.setVideoModelId(88L);
        series.setImageModelId(77L);
        series.setVideoGenerateMode(DramaVideoGenerateModes.I2V);
        when(seriesMapper.selectById(21L)).thenReturn(series);
        when(aiModelMapper.selectById(88L)).thenReturn(seedanceModel());

        BizException error = assertThrows(BizException.class, () -> service.generateShot(201L));
        assertTrue(error.getMessage().contains("首帧"));
        verify(generateService, never()).submit(any(GenerateSubmitReq.class), any());
}

    @Test
    void generateI2vExtractsPrevTailFromVideoWhenMissing() {
        DramaChapter chapter = pendingChapter();
        storedChapters.add(chapter);
        DramaShot first = pendingShot();
        first.setId(200L);
        first.setShotIndex(1);
        first.setChapterId(chapter.getId());
        first.setVideoKey("users/7/gen/shot1.mp4");
        storedShots.add(first);
        DramaShot second = pendingShot();
        second.setId(201L);
        second.setShotIndex(2);
        second.setChapterId(chapter.getId());
        storedShots.add(asI2v(second));
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER)));
        DramaSeries series = ownedSeries();
        series.setVideoModelId(88L);
        series.setVideoGenerateMode(DramaVideoGenerateModes.I2V);
        when(seriesMapper.selectById(21L)).thenReturn(series);
        when(aiModelMapper.selectById(88L)).thenReturn(seedanceModel());

        BizException error = assertThrows(BizException.class, () -> service.generateShot(201L));
        assertTrue(error.getMessage().contains("首帧"));
        verify(generateService, never()).submit(any(GenerateSubmitReq.class), any());
}

    @Test
    void clearFirstFrameFallsBackToPrevTail() {
        DramaChapter chapter = pendingChapter();
        storedChapters.add(chapter);
        DramaShot first = pendingShot();
        first.setId(200L);
        first.setShotIndex(1);
        first.setChapterId(chapter.getId());
        first.setVideoKey("users/7/gen/shot1.mp4");
        storedShots.add(first);
        DramaShot second = pendingShot();
        second.setId(201L);
        second.setShotIndex(2);
        second.setChapterId(chapter.getId());
        second.setFirstFrameKey("users/7/drama/21/frames/auto.webp");
        second.setFirstFrameOrigin("auto");
        storedShots.add(second);
        when(episodeMapper.selectById(31L)).thenReturn(ownedEpisode());
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER)));
        when(posterExtractor.extractTail("users/7/gen/shot1.mp4")).thenReturn("users/7/gen/shot1-tail.webp");

        DramaShotResp resp = service.clearFirstFrame(201L);

        org.junit.jupiter.api.Assertions.assertNull(second.getFirstFrameKey());
        assertEquals("prev-tail", resp.getFirstFrameSource());
        assertEquals("users/7/gen/shot1-tail.webp", resp.getEffectiveFirstFrameKey());
        assertEquals("users/7/gen/shot1-tail.webp", first.getVideoTailKey());
    }

    @Test
    void usePrevTailClearsAutoStillForSameChapter() {
        DramaChapter chapter = pendingChapter();
        storedChapters.add(chapter);
        DramaShot first = pendingShot();
        first.setId(200L);
        first.setShotIndex(1);
        first.setChapterId(chapter.getId());
        first.setVideoKey("users/7/gen/shot1.mp4");
        storedShots.add(first);
        DramaShot second = pendingShot();
        second.setId(201L);
        second.setShotIndex(2);
        second.setChapterId(chapter.getId());
        second.setNarrativeGoal("特写。洛白手中古铜怀表。");
        second.setFirstFrameKey("users/7/drama/21/frames/auto.webp");
        second.setFirstFrameOrigin("auto");
        storedShots.add(second);
        when(episodeMapper.selectById(31L)).thenReturn(ownedEpisode());
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER)));
        when(posterExtractor.extractTail("users/7/gen/shot1.mp4")).thenReturn("users/7/gen/shot1-tail.webp");

        DramaShotResp resp = service.usePrevTail(201L);

        assertEquals("users/7/gen/shot1-tail.webp", second.getFirstFrameKey());
        assertEquals("prev-tail", second.getFirstFrameOrigin());
        assertEquals("prev-tail", resp.getFirstFrameSource());
        assertEquals("users/7/gen/shot1-tail.webp", resp.getEffectiveFirstFrameKey());
        assertEquals(Boolean.TRUE, resp.getCanUsePrevTail());
    }

    @Test
    void generateI2vWaitsWhenPrevShotStillInFlight() {
        DramaChapter chapter = pendingChapter();
        storedChapters.add(chapter);
        DramaShot first = pendingShot();
        first.setId(200L);
        first.setShotIndex(1);
        first.setChapterId(chapter.getId());
        first.setGenTaskId(499L);
        storedShots.add(first);
        DramaShot second = pendingShot();
        second.setId(201L);
        second.setShotIndex(2);
        second.setChapterId(chapter.getId());
        storedShots.add(asI2v(second));
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER)));
        DramaSeries series = ownedSeries();
        series.setVideoModelId(88L);
        series.setImageModelId(77L);
        series.setVideoGenerateMode(DramaVideoGenerateModes.I2V);
        when(seriesMapper.selectById(21L)).thenReturn(series);
        when(aiModelMapper.selectById(88L)).thenReturn(seedanceModel());

        BizException error = assertThrows(BizException.class, () -> service.generateShot(201L));
        assertTrue(error.getMessage().contains("首帧"));
        verify(generateService, never()).submit(any(GenerateSubmitReq.class), any());
}

    @Test
    void getStoryboardKeepsI2vCompiledPromptAfterRefresh() {
        DramaChapter chapter = pendingChapter();
        storedChapters.add(chapter);
        DramaShot first = pendingShot();
        first.setId(200L);
        first.setShotIndex(1);
        first.setChapterId(chapter.getId());
        storedShots.add(first);
        DramaShot shot = asI2v(pendingShot());
        shot.setId(201L);
        shot.setShotIndex(2);
        shot.setChapterId(chapter.getId());
        storedShots.add(shot);
        when(episodeMapper.selectById(31L)).thenReturn(ownedEpisode());
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER),
                lockedAsset(5L, "幻梦侦探社", DramaContract.ASSET_TYPE_SCENE)));
        DramaSeries series = ownedSeries();
        series.setVideoGenerateMode(DramaVideoGenerateModes.I2V);
        when(seriesMapper.selectById(21L)).thenReturn(series);

        service.compileShot(201L);
        String prompt = shot.getCompiledPrompt();
        assertNotNull(prompt);
        assertFalse(prompt.contains(DramaStoryboardCompiler.I2V_MUST_START));
        assertTrue(prompt.contains("洛白身份不变量"));
        assertTrue(prompt.contains("<Picture"));
        assertNotNull(shot.getVisiblePrompt());
        assertNull(shot.getPromptOverride());

        DramaStoryboardResp board = service.getStoryboard(31L);
        DramaShotResp visible = board.getChapters().get(0).getShots().get(1);
        assertEquals(prompt, visible.getCompiledPrompt());
        assertFalse(Boolean.TRUE.equals(visible.getCompiledStale()));
    }

    @Test
    void getStoryboardFillsEmptyCompiledPrompt() {
        storedChapters.add(pendingChapter());
        DramaShot shot = pendingShot();
        shot.setCompiledPrompt(null);
        shot.setCompiledFingerprint(null);
        storedShots.add(shot);
        when(episodeMapper.selectById(31L)).thenReturn(ownedEpisode());
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER),
                lockedAsset(5L, "幻梦侦探社", DramaContract.ASSET_TYPE_SCENE)));

        DramaStoryboardResp board = service.getStoryboard(31L);

        DramaShotResp visible = board.getChapters().get(0).getShots().get(0);
        assertNotNull(visible.getCompiledPrompt());
        assertFalse(visible.getCompiledPrompt().isBlank());
        assertTrue(visible.getCompiledPrompt().contains("<Picture"));
        assertFalse(Boolean.TRUE.equals(visible.getCompiledStale()));
    }

    @Test
    void switchingGenerateModeRecompilesPromptOnRefresh() {
        DramaChapter chapter = pendingChapter();
        storedChapters.add(chapter);
        DramaShot first = pendingShot();
        first.setId(200L);
        first.setShotIndex(1);
        first.setChapterId(chapter.getId());
        storedShots.add(first);
        DramaShot shot = pendingShot();
        shot.setId(201L);
        shot.setShotIndex(2);
        shot.setChapterId(chapter.getId());
        storedShots.add(shot);
        when(episodeMapper.selectById(31L)).thenReturn(ownedEpisode());
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER),
                lockedAsset(5L, "幻梦侦探社", DramaContract.ASSET_TYPE_SCENE)));
        DramaSeries series = ownedSeries();
        series.setVideoGenerateMode(DramaVideoGenerateModes.R2V);
        when(seriesMapper.selectById(21L)).thenReturn(series);

        service.compileShot(201L);
        assertTrue(shot.getCompiledPrompt().contains("<Picture"));
        assertFalse(shot.getCompiledPrompt().contains(DramaStoryboardCompiler.I2V_MUST_START));
        assertFalse(shot.getCompiledPrompt().contains(DramaStoryboardCompiler.I2V_IDENTITIES_HEADER));

        shot.setVideoGenerateMode(DramaVideoGenerateModes.I2V);
        DramaStoryboardResp board = service.getStoryboard(31L);
        DramaShotResp visible = board.getChapters().get(0).getShots().get(1);
        assertTrue(visible.getCompiledPrompt().contains("<Picture"));
        assertFalse(visible.getCompiledPrompt().contains(DramaStoryboardCompiler.I2V_MUST_START));
        assertFalse(visible.getCompiledPrompt().contains(DramaStoryboardCompiler.I2V_IDENTITIES_HEADER));
        assertFalse(Boolean.TRUE.equals(visible.getCompiledStale()));
    }

    @Test
    void compileShotHonorsShotOverrideWhenSeriesIsR2v() {
        DramaChapter chapter = pendingChapter();
        storedChapters.add(chapter);
        DramaShot first = pendingShot();
        first.setId(200L);
        first.setShotIndex(1);
        first.setChapterId(chapter.getId());
        storedShots.add(first);
        DramaShot shot = pendingShot();
        shot.setId(201L);
        shot.setShotIndex(2);
        shot.setChapterId(chapter.getId());
        shot.setVideoGenerateMode(DramaVideoGenerateModes.I2V);
        storedShots.add(shot);
        when(episodeMapper.selectById(31L)).thenReturn(ownedEpisode());
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER),
                lockedAsset(5L, "幻梦侦探社", DramaContract.ASSET_TYPE_SCENE)));
        DramaSeries series = ownedSeries();
        series.setVideoGenerateMode(DramaVideoGenerateModes.R2V);
        when(seriesMapper.selectById(21L)).thenReturn(series);

        DramaShotResp resp = service.compileShot(201L);

        assertTrue(shot.getCompiledPrompt().contains("<Picture"));
        assertFalse(shot.getCompiledPrompt().contains(DramaStoryboardCompiler.I2V_MUST_START));
        assertEquals(DramaVideoGenerateModes.I2V, resp.getEffectiveVideoGenerateMode());
        assertEquals(DramaVideoGenerateModes.I2V, resp.getVideoGenerateMode());
        assertEquals(Boolean.FALSE, resp.getVideoModeInherited());
        assertEquals(Boolean.TRUE, resp.getVideoChainInherited());
    }

    @Test
    void generateShotUsesShotI2vWhileSeriesDefaultIsR2v() {
        DramaChapter chapter = pendingChapter();
        storedChapters.add(chapter);
        DramaShot first = pendingShot();
        first.setId(200L);
        first.setShotIndex(1);
        first.setChapterId(chapter.getId());
        first.setVideoTailKey("users/7/gen/shot1-tail.webp");
        storedShots.add(first);
        DramaShot second = pendingShot();
        second.setId(201L);
        second.setShotIndex(2);
        second.setChapterId(chapter.getId());
        second.setVideoGenerateMode(DramaVideoGenerateModes.I2V);
        second.setFirstFrameKey("users/7/drama/21/frames/open.webp");
        second.setEndFrameKey("users/7/drama/21/frames/end.webp");
        storedShots.add(second);
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER),
                lockedAsset(5L, "幻梦侦探社", DramaContract.ASSET_TYPE_SCENE)));
        DramaSeries series = ownedSeries();
        series.setVideoModelId(88L);
        series.setVideoGenerateMode(DramaVideoGenerateModes.R2V);
        series.setAspectRatio("16:9");
        when(seriesMapper.selectById(21L)).thenReturn(series);
        when(aiModelMapper.selectById(88L)).thenReturn(seedanceModel());
        GenerateTaskResp task = new GenerateTaskResp();
        task.setId(500L);
        task.setStatus("PENDING");
        when(generateService.submit(any(GenerateSubmitReq.class), any())).thenReturn(task);

        service.generateShot(201L);

        ArgumentCaptor<GenerateSubmitReq> reqCaptor = ArgumentCaptor.forClass(GenerateSubmitReq.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> extraCaptor = ArgumentCaptor.forClass(java.util.Map.class);
        verify(generateService).submit(reqCaptor.capture(), extraCaptor.capture());
        assertEquals("users/7/drama/21/frames/open.webp", reqCaptor.getValue().getFrames().getFirstFrameKey());
        assertEquals("users/7/drama/21/frames/end.webp", reqCaptor.getValue().getFrames().getLastFrameKey());
        assertNull(reqCaptor.getValue().getReferences());
        assertEquals(DramaVideoGenerateModes.I2V, extraCaptor.getValue().get("videoGenerateMode"));
        assertEquals(DramaVideoGenerateModes.I2V, second.getVideoGenerateMode());
    }

    @Test
    void recompileSeriesKeepingOverridesSkipsExplicitModeAndPromptOverride() {
        storedChapters.add(pendingChapter());
        DramaShot inherit = pendingShot();
        inherit.setId(201L);
        inherit.setShotIndex(1);
        inherit.setCompiledPrompt("old-inherit");
        DramaShot explicit = pendingShot();
        explicit.setId(202L);
        explicit.setShotIndex(2);
        explicit.setVideoGenerateMode(DramaVideoGenerateModes.I2V);
        explicit.setCompiledPrompt("keep-i2v");
        explicit.setCompiledFingerprint("keep");
        DramaShot overridden = pendingShot();
        overridden.setId(203L);
        overridden.setShotIndex(3);
        overridden.setPromptOverride("hand-written");
        overridden.setCompiledPrompt("old-overridden");
        storedShots.add(inherit);
        storedShots.add(explicit);
        storedShots.add(overridden);
        when(episodeMapper.selectList(any())).thenReturn(List.of(ownedEpisode()));
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER)));
        DramaSeries series = ownedSeries();
        series.setVideoGenerateMode(DramaVideoGenerateModes.R2V);
        when(seriesMapper.selectById(21L)).thenReturn(series);

        service.recompileSeriesKeepingOverrides(21L);

        assertTrue(inherit.getCompiledPrompt().contains("<Picture"));
        assertEquals("keep-i2v", explicit.getCompiledPrompt());
        assertEquals("hand-written", overridden.getPromptOverride());
        assertEquals("old-overridden", overridden.getCompiledPrompt());
    }

    @Test
    void switchingSeriesModeDoesNotStaleExplicitShot() {
        storedChapters.add(pendingChapter());
        DramaShot inherit = pendingShot();
        inherit.setId(201L);
        inherit.setShotIndex(1);
        DramaShot explicit = pendingShot();
        explicit.setId(202L);
        explicit.setShotIndex(2);
        explicit.setVideoGenerateMode(DramaVideoGenerateModes.R2V);
        storedShots.add(inherit);
        storedShots.add(explicit);
        when(episodeMapper.selectById(31L)).thenReturn(ownedEpisode());
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER),
                lockedAsset(5L, "幻梦侦探社", DramaContract.ASSET_TYPE_SCENE)));
        DramaSeries series = ownedSeries();
        series.setVideoGenerateMode(DramaVideoGenerateModes.R2V);
        when(seriesMapper.selectById(21L)).thenReturn(series);

        service.compileShot(201L);
        service.compileShot(202L);
        String keep = explicit.getCompiledPrompt();
        assertTrue(keep.contains("<Picture"));

        series.setVideoGenerateMode(DramaVideoGenerateModes.I2V);
        DramaStoryboardResp board = service.getStoryboard(31L);
        assertNotNull(inherit.getCompiledPrompt());
        assertEquals(keep, explicit.getCompiledPrompt());
        DramaShotResp inheritResp = board.getChapters().get(0).getShots().get(0);
        DramaShotResp explicitResp = board.getChapters().get(0).getShots().get(1);
        assertEquals(DramaVideoGenerateModes.I2V, inheritResp.getEffectiveVideoGenerateMode());
        assertEquals(Boolean.TRUE, inheritResp.getVideoModeInherited());
        assertEquals(DramaVideoGenerateModes.R2V, explicitResp.getEffectiveVideoGenerateMode());
        assertEquals(Boolean.FALSE, explicitResp.getVideoModeInherited());
        assertFalse(Boolean.TRUE.equals(explicitResp.getCompiledStale()));
        assertFalse(Boolean.TRUE.equals(inheritResp.getCompiledStale()));
    }

    @Test
    void firstShotInChapterCanSaveI2v() {
        storedChapters.add(pendingChapter());
        DramaShot shot = pendingShot();
        storedShots.add(shot);
        when(assetMapper.selectList(any())).thenReturn(List.of());
        DramaSeries series = ownedSeries();
        series.setVideoModelId(88L);
        when(seriesMapper.selectById(21L)).thenReturn(series);
        when(aiModelMapper.selectById(88L)).thenReturn(r2vModel());

        DramaShotSaveReq req = new DramaShotSaveReq();
        req.setVideoGenerateMode(DramaVideoGenerateModes.I2V);
        DramaShotResp resp = service.updateShot(201L, req);

        assertEquals(DramaVideoGenerateModes.I2V, shot.getVideoGenerateMode());
        assertEquals(DramaVideoGenerateModes.I2V, resp.getVideoGenerateMode());
    }

    @Test
    void batchLockBlocksUpdateShot() {
        storedChapters.add(pendingChapter());
        DramaShot shot = pendingShot();
        storedShots.add(shot);
        DramaShotBatchGenerateService batch = org.mockito.Mockito.mock(DramaShotBatchGenerateService.class);
        org.mockito.Mockito.doThrow(new BizException(
                        cn.aigc.dit.common.exception.ErrorCode.BAD_REQUEST, "该镜正在批量生成中"))
                .when(batch).assertNotBatchLocked(201L);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "batchGenerateService", batch);

        DramaShotSaveReq req = new DramaShotSaveReq();
        req.setTitle("改名");
        BizException error = assertThrows(BizException.class, () -> service.updateShot(201L, req));
        assertTrue(error.getMessage().contains("批量生成"));
    }

    @Test
    void firstShotInChapterKeepsI2vWhenSeriesIsI2v() {
        storedChapters.add(pendingChapter());
        DramaShot shot = pendingShot();
        storedShots.add(shot);
        when(episodeMapper.selectById(31L)).thenReturn(ownedEpisode());
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER),
                lockedAsset(5L, "幻梦侦探社", DramaContract.ASSET_TYPE_SCENE)));
        DramaSeries series = ownedSeries();
        series.setVideoGenerateMode(DramaVideoGenerateModes.I2V);
        when(seriesMapper.selectById(21L)).thenReturn(series);

        DramaShotResp resp = service.compileShot(201L);

        assertEquals(DramaVideoGenerateModes.I2V, resp.getEffectiveVideoGenerateMode());
        assertTrue(shot.getCompiledPrompt().contains("<Picture"));
        assertFalse(shot.getCompiledPrompt().contains(DramaStoryboardCompiler.I2V_MUST_START));
    }

    @Test
    void updateShotInheritClearsModeOverride() {
        storedChapters.add(pendingChapter());
        DramaShot shot = pendingShot();
        shot.setVideoGenerateMode(DramaVideoGenerateModes.I2V);
        shot.setVideoChainPrevTail(true);
        storedShots.add(shot);
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER)));

        DramaShotSaveReq req = new DramaShotSaveReq();
        req.setVideoGenerateMode(DramaVideoGenerateModes.INHERIT);
        DramaShotResp resp = service.updateShot(201L, req);

        assertEquals(null, shot.getVideoGenerateMode());
        assertEquals(null, shot.getVideoChainPrevTail());
        assertEquals(Boolean.TRUE, resp.getVideoModeInherited());
        assertEquals(Boolean.TRUE, resp.getVideoChainInherited());
        assertEquals(DramaVideoGenerateModes.R2V, resp.getEffectiveVideoGenerateMode());
        assertFalse(resp.getEffectiveVideoChainPrevTail());
        assertTrue(shot.getCompiledPrompt().contains("<Picture"));
    }

    @Test
    void nonFirstShotDefaultsToChainingPrevTail() {
        storedChapters.add(pendingChapter());
        DramaShot first = pendingShot();
        first.setId(200L);
        first.setShotIndex(1);
        storedShots.add(first);
        DramaShot second = pendingShot();
        second.setId(201L);
        second.setShotIndex(2);
        second.setTitle("接下一段");
        storedShots.add(second);
        when(episodeMapper.selectById(31L)).thenReturn(ownedEpisode());
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER)));
        DramaSeries series = ownedSeries();
        series.setVideoChainPrevTail(false);
        when(seriesMapper.selectById(21L)).thenReturn(series);

        DramaShotResp resp = service.compileShot(201L);

        assertEquals(null, second.getVideoChainPrevTail());
        assertEquals(Boolean.TRUE, resp.getEffectiveVideoChainPrevTail());
        assertFalse(second.getCompiledPrompt().contains(DramaStoryboardCompiler.R2V_CHAIN_HINT));
    }

    @Test
    void explicitUncheckKeepsNonFirstShotFromChainingPrevTail() {
        storedChapters.add(pendingChapter());
        DramaShot first = pendingShot();
        first.setId(200L);
        first.setShotIndex(1);
        storedShots.add(first);
        DramaShot second = pendingShot();
        second.setId(201L);
        second.setShotIndex(2);
        second.setVideoChainPrevTail(false);
        storedShots.add(second);
        when(episodeMapper.selectById(31L)).thenReturn(ownedEpisode());
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER)));
        DramaSeries series = ownedSeries();
        series.setVideoChainPrevTail(true);
        when(seriesMapper.selectById(21L)).thenReturn(series);

        DramaShotResp resp = service.compileShot(201L);

        assertEquals(Boolean.FALSE, resp.getEffectiveVideoChainPrevTail());
        assertFalse(second.getCompiledPrompt().contains(DramaStoryboardCompiler.R2V_CHAIN_HINT));
    }

    @Test
    void generateI2vOpeningStillWithGptUsesAllShotReferencesAsI2i() {
        DramaChapter chapter = pendingChapter();
        storedChapters.add(chapter);
        DramaShot first = pendingShot();
        first.setId(200L);
        first.setShotIndex(1);
        first.setChapterId(chapter.getId());
        first.setVideoTailKey("users/7/gen/shot1-tail.webp");
        first.setCharacterNames(List.of("洛白"));
        storedShots.add(first);
        DramaShot shot = asI2v(pendingShot());
        shot.setId(201L);
        shot.setShotIndex(2);
        shot.setChapterId(chapter.getId());
        shot.setCharacterNames(List.of("洛白", "局长"));
        shot.setPropNames(List.of("怀表"));
        shot.setLocationName("侦探社");
        shot.setNarrativeGoal("中景。局长推门将档案摔桌，洛白起身。");
        storedShots.add(shot);
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER),
                lockedAsset(2L, "局长", DramaContract.ASSET_TYPE_CHARACTER),
                lockedAsset(3L, "怀表", DramaContract.ASSET_TYPE_PROP),
                lockedAsset(4L, "侦探社", DramaContract.ASSET_TYPE_SCENE)));
        DramaSeries series = ownedSeries();
        series.setVideoModelId(88L);
        series.setImageModelId(77L);
        series.setVideoGenerateMode(DramaVideoGenerateModes.I2V);
        when(seriesMapper.selectById(21L)).thenReturn(series);
        when(aiModelMapper.selectById(88L)).thenReturn(seedanceModel());

        BizException error = assertThrows(BizException.class, () -> service.generateShot(201L));
        assertTrue(error.getMessage().contains("首帧"));
        verify(generateService, never()).submit(any(GenerateSubmitReq.class), any());
}

    @Test
    void generateI2vOpeningStillWithoutI2iFallsBackToT2iWithoutPictureRefs() {
        DramaChapter chapter = pendingChapter();
        storedChapters.add(chapter);
        DramaShot first = pendingShot();
        first.setId(200L);
        first.setShotIndex(1);
        first.setChapterId(chapter.getId());
        first.setVideoTailKey("users/7/gen/shot1-tail.webp");
        first.setCharacterNames(List.of("洛白"));
        storedShots.add(first);
        DramaShot second = asI2v(pendingShot());
        second.setId(201L);
        second.setShotIndex(2);
        second.setChapterId(chapter.getId());
        second.setCharacterNames(List.of("局长", "洛白"));
        second.setNarrativeGoal("中景。局长推门将档案摔桌，洛白起身。");
        storedShots.add(second);
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER),
                lockedAsset(3L, "局长", DramaContract.ASSET_TYPE_CHARACTER)));
        DramaSeries series = ownedSeries();
        series.setVideoModelId(88L);
        series.setImageModelId(77L);
        series.setVideoGenerateMode(DramaVideoGenerateModes.I2V);
        when(seriesMapper.selectById(21L)).thenReturn(series);
        when(aiModelMapper.selectById(88L)).thenReturn(seedanceModel());

        BizException error = assertThrows(BizException.class, () -> service.generateShot(201L));
        assertTrue(error.getMessage().contains("首帧"));
        verify(generateService, never()).submit(any(GenerateSubmitReq.class), any());
}

    @Test
    void generateI2vWithoutFirstFrameStartsOpeningStill() {
        DramaChapter chapter = pendingChapter();
        storedChapters.add(chapter);
        DramaShot first = pendingShot();
        first.setId(200L);
        first.setShotIndex(1);
        first.setChapterId(chapter.getId());
        first.setVideoTailKey("users/7/gen/shot1-tail.webp");
        first.setCharacterNames(List.of("洛白"));
        storedShots.add(first);
        DramaShot second = asI2v(pendingShot());
        second.setId(201L);
        second.setShotIndex(2);
        second.setChapterId(chapter.getId());
        second.setCharacterNames(List.of("局长", "洛白"));
        second.setNarrativeGoal("中景。局长推门将档案摔桌，洛白起身。");
        storedShots.add(second);
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER),
                lockedAsset(3L, "局长", DramaContract.ASSET_TYPE_CHARACTER)));
        DramaSeries series = ownedSeries();
        series.setVideoModelId(88L);
        series.setImageModelId(77L);
        series.setVideoGenerateMode(DramaVideoGenerateModes.I2V);
        when(seriesMapper.selectById(21L)).thenReturn(series);
        when(aiModelMapper.selectById(88L)).thenReturn(seedanceModel());

        BizException error = assertThrows(BizException.class, () -> service.generateShot(201L));
        assertTrue(error.getMessage().contains("首帧"));
        verify(generateService, never()).submit(any(GenerateSubmitReq.class), any());
}

    @Test
    void generateI2vWithoutImageModelRejected() {
        DramaChapter chapter = pendingChapter();
        storedChapters.add(chapter);
        DramaShot first = pendingShot();
        first.setId(200L);
        first.setShotIndex(1);
        first.setChapterId(chapter.getId());
        first.setVideoTailKey("users/7/gen/shot1-tail.webp");
        first.setCharacterNames(List.of("洛白"));
        storedShots.add(first);
        DramaShot second = asI2v(pendingShot());
        second.setId(201L);
        second.setShotIndex(2);
        second.setChapterId(chapter.getId());
        second.setCharacterNames(List.of("局长", "洛白"));
        second.setNarrativeGoal("中景。局长推门将档案摔桌，洛白起身。");
        storedShots.add(second);
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER),
                lockedAsset(3L, "局长", DramaContract.ASSET_TYPE_CHARACTER)));
        DramaSeries series = ownedSeries();
        series.setVideoModelId(88L);
        series.setVideoGenerateMode(DramaVideoGenerateModes.I2V);
        when(seriesMapper.selectById(21L)).thenReturn(series);
        when(aiModelMapper.selectById(88L)).thenReturn(seedanceModel());

        BizException error = assertThrows(BizException.class, () -> service.generateShot(201L));
        assertTrue(error.getMessage().contains("首帧"));
        verify(generateService, never()).submit(any(GenerateSubmitReq.class), any());
}

    @Test
    void generateI2vDoesNotChainAcrossChapters() {
        DramaChapter firstChapter = pendingChapter();
        storedChapters.add(firstChapter);
        DramaChapter secondChapter = pendingChapter();
        secondChapter.setId(91L);
        secondChapter.setChapterIndex(2);
        secondChapter.setSceneIndex(2);
        storedChapters.add(secondChapter);
        DramaShot first = pendingShot();
        first.setId(200L);
        first.setShotIndex(1);
        first.setChapterId(firstChapter.getId());
        first.setVideoTailKey("users/7/gen/shot1-tail.webp");
        storedShots.add(first);
        DramaShot second = pendingShot();
        second.setId(201L);
        second.setShotIndex(2);
        second.setChapterId(secondChapter.getId());
        storedShots.add(asI2v(second));
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER),
                lockedAsset(5L, "幻梦侦探社", DramaContract.ASSET_TYPE_SCENE)));
        DramaSeries series = ownedSeries();
        series.setVideoModelId(88L);
        series.setImageModelId(77L);
        series.setVideoGenerateMode(DramaVideoGenerateModes.I2V);
        series.setAspectRatio("16:9");
        when(seriesMapper.selectById(21L)).thenReturn(series);
        when(aiModelMapper.selectById(88L)).thenReturn(r2vModel());

        BizException error = assertThrows(BizException.class, () -> service.generateShot(201L));
        assertTrue(error.getMessage().contains("首帧"));
        verify(generateService, never()).submit(any(GenerateSubmitReq.class), any());
}

    @Test
    void generateI2vCloseUpDoesNotUseWideTail() {
        DramaChapter chapter = pendingChapter();
        storedChapters.add(chapter);
        DramaShot first = pendingShot();
        first.setId(200L);
        first.setShotIndex(1);
        first.setChapterId(chapter.getId());
        first.setNarrativeGoal("全景。办公室日常。");
        first.setVideoTailKey("users/7/gen/shot1-tail.webp");
        storedShots.add(first);
        DramaShot close = pendingShot();
        close.setId(201L);
        close.setShotIndex(2);
        close.setChapterId(chapter.getId());
        close.setNarrativeGoal("特写。洛白手中古铜怀表。");
        storedShots.add(asI2v(close));
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER)));
        DramaSeries series = ownedSeries();
        series.setVideoModelId(88L);
        series.setImageModelId(77L);
        series.setVideoGenerateMode(DramaVideoGenerateModes.I2V);
        when(seriesMapper.selectById(21L)).thenReturn(series);
        when(aiModelMapper.selectById(88L)).thenReturn(seedanceModel());

        BizException error = assertThrows(BizException.class, () -> service.generateShot(201L));
        assertTrue(error.getMessage().contains("首帧"));
        verify(generateService, never()).submit(any(GenerateSubmitReq.class), any());
}

    @Test
    void generateOpeningStillSameChapterAddsPrevTailReference() {
        DramaChapter chapter = pendingChapter();
        storedChapters.add(chapter);
        DramaShot first = pendingShot();
        first.setId(200L);
        first.setShotIndex(1);
        first.setChapterId(chapter.getId());
        first.setVideoKey("users/7/gen/shot1.mp4");
        first.setVideoTailKey("users/7/gen/shot1-tail.webp");
        storedShots.add(first);
        DramaShot close = pendingShot();
        close.setId(201L);
        close.setShotIndex(2);
        close.setChapterId(chapter.getId());
        close.setNarrativeGoal("特写。洛白手中古铜怀表。");
        storedShots.add(asI2v(close));
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER)));
        DramaSeries series = ownedSeries();
        series.setImageModelId(77L);
        series.setVideoGenerateMode(DramaVideoGenerateModes.I2V);
        when(seriesMapper.selectById(21L)).thenReturn(series);
        when(aiModelMapper.selectById(77L)).thenReturn(imageModel());
        GenerateTaskResp still = new GenerateTaskResp();
        still.setId(603L);
        still.setStatus("PENDING");
        when(generateService.submit(any(GenerateSubmitReq.class), any())).thenReturn(still);

        service.generateOpeningStill(201L);

        ArgumentCaptor<GenerateSubmitReq> reqCaptor = ArgumentCaptor.forClass(GenerateSubmitReq.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> extraCaptor = ArgumentCaptor.forClass(java.util.Map.class);
        verify(generateService).submit(reqCaptor.capture(), extraCaptor.capture());
        assertEquals("image", reqCaptor.getValue().getCategory());
        assertEquals("users/7/gen/shot1-tail.webp", reqCaptor.getValue().getReferences().get(1).getStorageKey());
        assertTrue(reqCaptor.getValue().getPrompt().contains(DramaStoryboardCompiler.STILL_PREV_TAIL_HINT));
        assertEquals(Boolean.FALSE, extraCaptor.getValue().get("stillForI2v"));
        assertEquals(Boolean.TRUE, extraCaptor.getValue().get("stillChainPrevTail"));
    }

    @Test
    void getGenerateTaskContinuesOpeningStillIntoI2v() {
        DramaChapter chapter = pendingChapter();
        storedChapters.add(chapter);
        DramaShot first = pendingShot();
        first.setId(200L);
        first.setShotIndex(1);
        first.setChapterId(chapter.getId());
        first.setVideoTailKey("users/7/gen/shot1-tail.webp");
        first.setCharacterNames(List.of("洛白"));
        storedShots.add(first);
        DramaShot shot = asI2v(pendingShot());
        shot.setId(201L);
        shot.setShotIndex(2);
        shot.setChapterId(chapter.getId());
        shot.setCharacterNames(List.of("局长", "洛白"));
        shot.setNarrativeGoal("中景。局长推门将档案摔桌，洛白起身。");
        storedShots.add(shot);
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER),
                lockedAsset(3L, "局长", DramaContract.ASSET_TYPE_CHARACTER)));
        DramaSeries series = ownedSeries();
        series.setVideoModelId(88L);
        series.setImageModelId(77L);
        series.setVideoGenerateMode(DramaVideoGenerateModes.I2V);
        series.setAspectRatio("16:9");
        when(seriesMapper.selectById(21L)).thenReturn(series);
        when(aiModelMapper.selectById(88L)).thenReturn(seedanceModel());
        GenerateTaskResp videoPending = new GenerateTaskResp();
        videoPending.setId(500L);
        videoPending.setStatus("PENDING");
        when(generateService.submit(any(GenerateSubmitReq.class), any())).thenReturn(videoPending);
        GenerateTaskResp stillDone = new GenerateTaskResp();
        stillDone.setId(600L);
        stillDone.setStatus("SUCCEEDED");
        stillDone.setCategory("image");
        stillDone.setOutputStorageKey("users/7/drama/21/frames/auto.webp");
        stillDone.setParams(java.util.Map.of(
                "source", GenerateRequests.SOURCE_DRAMA,
                "shotId", 201L,
                "openingStill", true,
                "stillForI2v", true));
        when(generateService.getMine(600L)).thenReturn(stillDone);
        shot.setGenTaskId(600L);

        service.getGenerateTask(201L, 600L);

        ArgumentCaptor<GenerateSubmitReq> reqCaptor = ArgumentCaptor.forClass(GenerateSubmitReq.class);
        verify(generateService).submit(reqCaptor.capture(), any());
        assertEquals("video", reqCaptor.getValue().getCategory());
        assertEquals("users/7/drama/21/frames/auto.webp", reqCaptor.getValue().getFrames().getFirstFrameKey());
        assertEquals("auto", shot.getFirstFrameOrigin());
        assertEquals(500L, shot.getGenTaskId());
    }

    @Test
    void generateI2vUsesUploadedFirstFrame() {
        DramaChapter chapter = pendingChapter();
        storedChapters.add(chapter);
        DramaShot first = pendingShot();
        first.setId(200L);
        first.setShotIndex(1);
        first.setChapterId(chapter.getId());
        first.setVideoTailKey("users/7/gen/shot1-tail.webp");
        storedShots.add(first);
        DramaShot shot = asI2v(pendingShot());
        shot.setId(201L);
        shot.setShotIndex(2);
        shot.setChapterId(chapter.getId());
        shot.setFirstFrameKey("users/7/drama/21/frames/open.webp");
        storedShots.add(shot);
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER)));
        DramaSeries series = ownedSeries();
        series.setVideoModelId(88L);
        series.setVideoGenerateMode(DramaVideoGenerateModes.I2V);
        when(seriesMapper.selectById(21L)).thenReturn(series);
        when(aiModelMapper.selectById(88L)).thenReturn(seedanceModel());
        GenerateTaskResp task = new GenerateTaskResp();
        task.setId(500L);
        task.setStatus("PENDING");
        when(generateService.submit(any(GenerateSubmitReq.class), any())).thenReturn(task);

        service.generateShot(201L);

        ArgumentCaptor<GenerateSubmitReq> reqCaptor = ArgumentCaptor.forClass(GenerateSubmitReq.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> extraCaptor = ArgumentCaptor.forClass(java.util.Map.class);
        verify(generateService).submit(reqCaptor.capture(), extraCaptor.capture());
        assertEquals("users/7/drama/21/frames/open.webp", reqCaptor.getValue().getFrames().getFirstFrameKey());
        assertNull(reqCaptor.getValue().getFrames().getLastFrameKey());
        assertNull(reqCaptor.getValue().getReferences());
        assertEquals(DramaVideoGenerateModes.I2V, extraCaptor.getValue().get("videoGenerateMode"));
    }

    @Test
    void generateR2vChainAddsPrevTailReference() {
        DramaChapter chapter = pendingChapter();
        storedChapters.add(chapter);
        DramaShot first = pendingShot();
        first.setId(200L);
        first.setShotIndex(1);
        first.setChapterId(chapter.getId());
        first.setVideoTailKey("users/7/gen/shot1-tail.webp");
        storedShots.add(first);
        DramaShot second = pendingShot();
        second.setId(201L);
        second.setShotIndex(2);
        second.setChapterId(chapter.getId());
        second.setVideoChainPrevTail(true);
        storedShots.add(second);
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER),
                lockedAsset(5L, "幻梦侦探社", DramaContract.ASSET_TYPE_SCENE)));
        DramaSeries series = ownedSeries();
        series.setVideoModelId(88L);
        series.setVideoChainPrevTail(true);
        when(seriesMapper.selectById(21L)).thenReturn(series);
        when(aiModelMapper.selectById(88L)).thenReturn(r2vModel());
        GenerateTaskResp task = new GenerateTaskResp();
        task.setId(501L);
        task.setStatus("PENDING");
        when(generateService.submit(any(GenerateSubmitReq.class), any())).thenReturn(task);

        service.generateShot(201L);

        ArgumentCaptor<GenerateSubmitReq> reqCaptor = ArgumentCaptor.forClass(GenerateSubmitReq.class);
        verify(generateService).submit(reqCaptor.capture(), any());
        GenerateSubmitReq req = reqCaptor.getValue();
        assertEquals(2, req.getReferences().size());
        assertTrue(req.getReferences().stream().noneMatch(item -> "users/7/gen/shot1-tail.webp".equals(item.getStorageKey())));
        assertFalse(req.getPrompt().contains(DramaStoryboardCompiler.PREV_TAIL_NAME));
        org.junit.jupiter.api.Assertions.assertNull(req.getFrames());
    }

    @Test
    void generateR2vInheritsSeriesChainWhenShotDoesNotOverrideIt() {
        DramaChapter chapter = pendingChapter();
        storedChapters.add(chapter);
        DramaShot first = pendingShot();
        first.setId(200L);
        first.setShotIndex(1);
        first.setChapterId(chapter.getId());
        first.setVideoTailKey("users/7/gen/shot1-tail.webp");
        storedShots.add(first);
        DramaShot second = pendingShot();
        second.setId(201L);
        second.setShotIndex(2);
        second.setChapterId(chapter.getId());
        storedShots.add(second);
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER),
                lockedAsset(5L, "幻梦侦探社", DramaContract.ASSET_TYPE_SCENE)));
        DramaSeries series = ownedSeries();
        series.setVideoModelId(88L);
        series.setVideoChainPrevTail(true);
        when(seriesMapper.selectById(21L)).thenReturn(series);
        when(aiModelMapper.selectById(88L)).thenReturn(r2vModel());
        GenerateTaskResp task = new GenerateTaskResp();
        task.setId(502L);
        task.setStatus("PENDING");
        when(generateService.submit(any(GenerateSubmitReq.class), any())).thenReturn(task);

        service.generateShot(201L);

        ArgumentCaptor<GenerateSubmitReq> reqCaptor = ArgumentCaptor.forClass(GenerateSubmitReq.class);
        verify(generateService).submit(reqCaptor.capture(), any());
        GenerateSubmitReq req = reqCaptor.getValue();
        assertEquals(2, req.getReferences().size());
        assertFalse(req.getPrompt().contains(DramaStoryboardCompiler.PREV_TAIL_NAME));
        org.junit.jupiter.api.Assertions.assertNull(req.getFrames());
    }

    @Test
    void generateOnOfficialSeedanceKeepsCharacterAndSceneImages() {
        DramaChapter chapter = pendingChapter();
        storedChapters.add(chapter);
        DramaShot shot = pendingShot();
        shot.setChapterId(chapter.getId());
        storedShots.add(shot);
        shot.setPropNames(List.of("古铜怀表"));
        shot.setNarrativeGoal("洛白拿着古铜怀表交代空间");
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
        assertEquals("users/7/assets/1.png", req.getReferences().get(1).getStorageKey());
        assertEquals("users/7/assets/8.png", req.getReferences().get(2).getStorageKey());
        assertTrue(req.getPrompt().contains("<Picture 1> 幻梦侦探社"));
        assertTrue(req.getPrompt().contains("<Picture 2> 洛白"));
        assertTrue(req.getPrompt().contains("<Picture 3> 古铜怀表"));
        assertFalse(req.getPrompt().contains("真实人脸"));
        assertFalse(req.getPrompt().contains("真人照片"));
        assertFalse(req.getPrompt().contains("real people"));
        assertEquals(List.of(5L, 1L, 8L), shot.getReferenceAssetIds());
    }

    @Test
    void generateOnOfficialSeedanceSendsOnlySpeakingCharactersVoiceWithMatchingPromptTag() {
        DramaChapter chapter = pendingChapter();
        storedChapters.add(chapter);
        DramaShot shot = pendingShot();
        shot.setChapterId(chapter.getId());
        shot.setCharacterNames(List.of("赵王", "秦国使臣"));
        shot.setCastCount(2);
        shot.setNarrativeGoal("赵王看着秦国使臣");
        shot.setDialogue("秦国使臣：秦王愿以十五座城，换赵国和氏璧。");
        storedShots.add(shot);

        DramaAsset king = lockedAsset(1L, "赵王", DramaContract.ASSET_TYPE_CHARACTER);
        king.setVoiceReferenceKey("users/7/assets/robot.mp3");
        king.setVoiceReferenceMime("audio/mpeg");
        DramaAsset envoy = lockedAsset(2L, "秦国使臣", DramaContract.ASSET_TYPE_CHARACTER);
        envoy.setVoiceReferenceKey("users/7/assets/envoy.mp3");
        envoy.setVoiceReferenceMime("audio/mpeg");
        when(assetMapper.selectList(any())).thenReturn(List.of(
                king,
                envoy,
                lockedAsset(5L, "幻梦侦探社", DramaContract.ASSET_TYPE_SCENE)));

        DramaSeries series = ownedSeries();
        series.setVideoModelId(88L);
        series.setAspectRatio("16:9");
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
        assertEquals(4, req.getReferences().size());
        assertEquals("audio", req.getReferences().get(3).getType());
        assertEquals("users/7/assets/envoy.mp3", req.getReferences().get(3).getStorageKey());
        assertFalse(req.getReferences().stream()
                .anyMatch(item -> "users/7/assets/robot.mp3".equals(item.getStorageKey())));
        assertTrue(req.getPrompt().contains("<Audio 1> 只定义角色“秦国使臣”的音色身份"));
        assertTrue(req.getPrompt().contains("角色“秦国使臣”使用 <Audio 1>"));
        assertFalse(req.getPrompt().contains("只定义角色“赵王”的音色身份"));
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
        assertEquals("users/7/assets/5.png", reqCaptor.getValue().getReferences().get(0).getStorageKey());
        assertEquals("users/7/assets/1.png", reqCaptor.getValue().getReferences().get(1).getStorageKey());
        assertEquals(List.of(5L, 1L), shot.getReferenceAssetIds());
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
        assertTrue(error.getMessage().contains("还没有参考图")
                || error.getMessage().contains("请先准备资产参考图")
                || error.getMessage().contains("缺少参考图"));
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
        task.setTailKey("users/7/gen/shot-tail.webp");
        when(generateService.getMine(500L)).thenReturn(task);

        DramaShotGenerateResp resp = service.getGenerateTask(201L, 500L);
        assertEquals("users/7/gen/shot.mp4", shot.getVideoKey());
        assertEquals("users/7/gen/shot-poster.webp", shot.getVideoPosterKey());
        assertEquals("users/7/gen/shot-tail.webp", shot.getVideoTailKey());
        assertNotNull(shot.getSelectedGenerationId());
        assertEquals("/api/media?key=users%2F7%2Fgen%2Fshot.mp4", resp.getShot().getVideoUrl());
        assertEquals("/api/media?key=users%2F7%2Fgen%2Fshot-poster.webp", resp.getShot().getVideoPosterUrl());
        assertFalse(Boolean.TRUE.equals(resp.getShot().getGenerating()));
    }

    @Test
    void selectShotGenerationRejectedWhenLocked() {
        DramaShot shot = pendingShot();
        shot.setStatus(DramaContract.STATUS_LOCKED);
        shot.setSelectedGenerationId(1L);
        shot.setVideoKey("users/7/gen/a.mp4");
        storedShots.add(shot);
        storedChapters.add(pendingChapter());

        BizException error = assertThrows(BizException.class, () -> service.selectGeneration(201L, 2L));
        assertEquals("分镜已锁定，无法切换最终分镜", error.getMessage());
    }

    @Test
    void applyTaskResultDoesNotDuplicateAfterSelectingOlderShotGeneration() {
        DramaShot shot = pendingShot();
        shot.setSelectedGenerationId(1L);
        shot.setVideoKey("users/7/gen/old.mp4");
        shot.setVideoPosterKey("users/7/gen/old-poster.webp");
        shot.setGenTaskId(500L);
        storedShots.add(shot);
        storedChapters.add(pendingChapter());
        when(assetMapper.selectList(any())).thenReturn(List.of());
        DramaShotGeneration newer = new DramaShotGeneration();
        newer.setId(2L);
        newer.setShotId(201L);
        newer.setUserId(7L);
        newer.setVideoKey("users/7/gen/new.mp4");
        newer.setGenTaskId(500L);
        when(shotGenerationMapper.selectOne(any())).thenReturn(newer);
        GenerateTaskResp task = new GenerateTaskResp();
        task.setId(500L);
        task.setStatus("SUCCEEDED");
        task.setOutputStorageKey("users/7/gen/new.mp4");
        task.setOutputKeys(List.of("users/7/gen/new.mp4"));
        task.setPosterKey("users/7/gen/new-poster.webp");
        when(generateService.getMine(500L)).thenReturn(task);

        service.getGenerateTask(201L, 500L);

        verify(shotGenerationMapper, never()).insert(any(DramaShotGeneration.class));
        assertEquals(1L, shot.getSelectedGenerationId());
        assertEquals("users/7/gen/old.mp4", shot.getVideoKey());
        assertEquals("users/7/gen/old-poster.webp", shot.getVideoPosterKey());
    }

    @Test
    void lockedShotCanDeleteUnselectedButNotSelectedGeneration() {
        DramaShot shot = pendingShot();
        shot.setStatus(DramaContract.STATUS_LOCKED);
        shot.setSelectedGenerationId(1L);
        shot.setVideoKey("users/7/gen/sel.mp4");
        storedShots.add(shot);
        DramaShotGeneration selected = new DramaShotGeneration();
        selected.setId(1L);
        selected.setShotId(201L);
        selected.setUserId(7L);
        selected.setVideoKey("users/7/gen/sel.mp4");
        DramaShotGeneration other = new DramaShotGeneration();
        other.setId(2L);
        other.setShotId(201L);
        other.setUserId(7L);
        other.setVideoKey("users/7/gen/other.mp4");
        when(shotGenerationMapper.selectById(1L)).thenReturn(selected);
        when(shotGenerationMapper.selectById(2L)).thenReturn(other);

        BizException error = assertThrows(BizException.class, () -> service.deleteGeneration(201L, 1L));
        assertEquals("分镜已锁定，不能删除当前选用记录", error.getMessage());

        service.deleteGeneration(201L, 2L);
        verify(shotGenerationMapper).deleteById(2L);
    }

    @Test
    void unlockedDeleteSelectedShotGenerationReselectsLatest() {
        DramaShot shot = pendingShot();
        shot.setSelectedGenerationId(2L);
        shot.setVideoKey("users/7/gen/new.mp4");
        storedShots.add(shot);
        DramaShotGeneration older = new DramaShotGeneration();
        older.setId(1L);
        older.setShotId(201L);
        older.setUserId(7L);
        older.setVideoKey("users/7/gen/old.mp4");
        older.setVideoPosterKey("users/7/gen/old-poster.webp");
        DramaShotGeneration selected = new DramaShotGeneration();
        selected.setId(2L);
        selected.setShotId(201L);
        selected.setUserId(7L);
        selected.setVideoKey("users/7/gen/new.mp4");
        when(shotGenerationMapper.selectById(2L)).thenReturn(selected);
        when(shotGenerationMapper.selectList(any())).thenReturn(List.of(older));

        service.deleteGeneration(201L, 2L);

        assertEquals(1L, shot.getSelectedGenerationId());
        assertEquals("users/7/gen/old.mp4", shot.getVideoKey());
        assertEquals("users/7/gen/old-poster.webp", shot.getVideoPosterKey());
    }

    @Test
    void deleteShotGenerationRemovesMatchingCreation() {
        DramaShot shot = pendingShot();
        shot.setSelectedGenerationId(1L);
        shot.setVideoKey("users/7/gen/keep.mp4");
        storedShots.add(shot);
        DramaShotGeneration selected = new DramaShotGeneration();
        selected.setId(1L);
        selected.setShotId(201L);
        selected.setUserId(7L);
        selected.setGenTaskId(400L);
        selected.setVideoKey("users/7/gen/keep.mp4");
        DramaShotGeneration dropped = new DramaShotGeneration();
        dropped.setId(2L);
        dropped.setShotId(201L);
        dropped.setUserId(7L);
        dropped.setGenTaskId(500L);
        dropped.setVideoKey("users/7/gen/drop.mp4");
        when(shotGenerationMapper.selectById(2L)).thenReturn(dropped);

        service.deleteGeneration(201L, 2L);

        verify(shotGenerationMapper).deleteById(2L);
        verify(creationService).removeDramaOutputs(7L, 500L, "users/7/gen/drop.mp4");
        verify(creationService, never()).removeDramaOutputs(7L, 400L, "users/7/gen/keep.mp4");
    }

    @Test
    void listGenerationsRenumbersLabelsByChronologicalOrder() {
        DramaShot shot = pendingShot();
        shot.setSelectedGenerationId(3L);
        storedShots.add(shot);
        DramaShotGeneration first = new DramaShotGeneration();
        first.setId(1L);
        first.setShotId(201L);
        first.setUserId(7L);
        first.setVideoKey("users/7/gen/a.mp4");
        first.setLabel("第 1 次生成");
        DramaShotGeneration second = new DramaShotGeneration();
        second.setId(2L);
        second.setShotId(201L);
        second.setUserId(7L);
        second.setVideoKey("users/7/gen/b.mp4");
        second.setLabel("第 2 次生成");
        DramaShotGeneration third = new DramaShotGeneration();
        third.setId(3L);
        third.setShotId(201L);
        third.setUserId(7L);
        third.setVideoKey("users/7/gen/c.mp4");
        // 模拟并发落库时写入了重复的「第 2 次生成」
        third.setLabel("第 2 次生成");
        when(shotGenerationMapper.selectList(any())).thenReturn(List.of(third, second, first));

        var list = service.listGenerations(201L);

        assertEquals(3, list.size());
        assertEquals("第 3 次生成", list.get(0).getLabel());
        assertEquals("第 2 次生成", list.get(1).getLabel());
        assertEquals("第 1 次生成", list.get(2).getLabel());
        assertTrue(list.get(0).getSelected());
    }

    @Test
    void regeneratingLockedShotUnlocksAndSelectsNewGeneration() {
        DramaShot shot = pendingShot();
        shot.setStatus(DramaContract.STATUS_LOCKED);
        shot.setSelectedGenerationId(88L);
        shot.setVideoKey("users/7/gen/old.mp4");
        shot.setGenTaskId(500L);
        storedShots.add(shot);
        storedChapters.add(pendingChapter());
        when(assetMapper.selectList(any())).thenReturn(List.of());
        GenerateTaskResp task = new GenerateTaskResp();
        task.setId(500L);
        task.setStatus("SUCCEEDED");
        task.setOutputStorageKey("users/7/gen/new.mp4");
        task.setOutputKeys(List.of("users/7/gen/new.mp4"));
        when(generateService.getMine(500L)).thenReturn(task);

        service.getGenerateTask(201L, 500L);

        assertEquals("users/7/gen/new.mp4", shot.getVideoKey());
        assertEquals(DramaContract.STATUS_PENDING_CONFIRM, shot.getStatus());
        assertNotNull(shot.getSelectedGenerationId());
        assertTrue(shot.getSelectedGenerationId() > 88L);
    }

    @Test
    void getGenerateTaskPublishesTailAndClearsNextAutoStill() {
        DramaChapter chapter = pendingChapter();
        storedChapters.add(chapter);
        DramaShot shot = pendingShot();
        shot.setId(201L);
        shot.setShotIndex(1);
        shot.setChapterId(chapter.getId());
        shot.setGenTaskId(500L);
        storedShots.add(shot);
        DramaShot next = pendingShot();
        next.setId(202L);
        next.setShotIndex(2);
        next.setChapterId(chapter.getId());
        next.setFirstFrameKey("users/7/drama/21/frames/auto.webp");
        next.setFirstFrameOrigin("auto");
        storedShots.add(next);
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER)));
        GenerateTaskResp task = new GenerateTaskResp();
        task.setId(500L);
        task.setStatus("SUCCEEDED");
        task.setOutputStorageKey("users/7/gen/shot.mp4");
        task.setOutputKeys(List.of("users/7/gen/shot.mp4"));
        task.setTailKey("users/7/gen/shot-tail.webp");
        when(generateService.getMine(500L)).thenReturn(task);

        service.getGenerateTask(201L, 500L);

        assertEquals("users/7/gen/shot-tail.webp", shot.getVideoTailKey());
        org.junit.jupiter.api.Assertions.assertNull(next.getFirstFrameKey());
        org.junit.jupiter.api.Assertions.assertNull(next.getFirstFrameOrigin());
    }

    @Test
    void getGenerateTaskDoesNotAutoLoadTailOntoCloseUp() {
        DramaChapter chapter = pendingChapter();
        storedChapters.add(chapter);
        DramaShot shot = pendingShot();
        shot.setId(201L);
        shot.setShotIndex(1);
        shot.setChapterId(chapter.getId());
        shot.setNarrativeGoal("中景。局长说话。");
        shot.setGenTaskId(500L);
        storedShots.add(shot);
        DramaShot close = pendingShot();
        close.setId(202L);
        close.setShotIndex(2);
        close.setChapterId(chapter.getId());
        close.setNarrativeGoal("特写。洛白手中古铜怀表。");
        close.setFirstFrameKey("users/7/drama/21/frames/auto.webp");
        close.setFirstFrameOrigin("auto");
        storedShots.add(close);
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER)));
        GenerateTaskResp task = new GenerateTaskResp();
        task.setId(500L);
        task.setStatus("SUCCEEDED");
        task.setOutputStorageKey("users/7/gen/shot.mp4");
        task.setOutputKeys(List.of("users/7/gen/shot.mp4"));
        task.setTailKey("users/7/gen/shot-tail.webp");
        when(generateService.getMine(500L)).thenReturn(task);

        service.getGenerateTask(201L, 500L);

        assertEquals("users/7/gen/shot-tail.webp", shot.getVideoTailKey());
        assertEquals("users/7/drama/21/frames/auto.webp", close.getFirstFrameKey());
        assertEquals("auto", close.getFirstFrameOrigin());
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
        assertEquals(null, created.getVideoGenerateMode());
        assertEquals(null, created.getVideoChainPrevTail());
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
    void createShotInsertsBeforeGivenShotAndRenumbers() {
        when(episodeMapper.selectById(31L)).thenReturn(ownedEpisode());
        when(assetMapper.selectList(any())).thenReturn(List.of());
        storedChapters.add(pendingChapter());
        for (int i = 1; i <= 2; i++) {
            DramaShot shot = pendingShot();
            shot.setId(200L + i);
            shot.setShotIndex(i);
            shot.setTitle("镜 " + i);
            storedShots.add(shot);
        }
        DramaShotCreateReq req = new DramaShotCreateReq();
        req.setBeforeShotId(201L);

        DramaShotResp resp = service.createShot(31L, req);

        assertEquals(Integer.valueOf(1), resp.getShotIndex());
        assertEquals(3, storedShots.size());
        assertEquals(2, storedShots.stream().filter(item -> item.getId() == 201L).findFirst().orElseThrow().getShotIndex());
        assertEquals(3, storedShots.stream().filter(item -> item.getId() == 202L).findFirst().orElseThrow().getShotIndex());
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
        generated.setGenTaskId(500L);
        storedShots.add(generated);
        DramaShot other = pendingShot();
        other.setId(202L);
        other.setShotIndex(2);
        storedShots.add(other);
        GenTask done = new GenTask();
        done.setId(500L);
        done.setStatus(TaskStatus.SUCCEEDED);
        when(genTaskMapper.selectById(500L)).thenReturn(done);

        DramaStoryboardResp board = service.deleteShot(201L);

        assertNotNull(board);
        assertEquals(1, storedShots.size());
        assertEquals(202L, storedShots.get(0).getId());
        assertEquals(1, storedShots.get(0).getShotIndex());
        verify(filmService).discardAssembledFilm(31L);
    }

    @Test
    void deleteShotRemovesGeneratedCreations() {
        when(episodeMapper.selectById(31L)).thenReturn(ownedEpisode());
        when(assetMapper.selectList(any())).thenReturn(List.of());
        storedChapters.add(pendingChapter());
        DramaShot generated = pendingShot();
        generated.setId(201L);
        generated.setShotIndex(1);
        generated.setVideoKey("users/7/videos/1.mp4");
        generated.setGenTaskId(500L);
        storedShots.add(generated);
        DramaShot other = pendingShot();
        other.setId(202L);
        other.setShotIndex(2);
        storedShots.add(other);
        GenTask done = new GenTask();
        done.setId(500L);
        done.setStatus(TaskStatus.SUCCEEDED);
        when(genTaskMapper.selectById(500L)).thenReturn(done);
        DramaShotGeneration generation = new DramaShotGeneration();
        generation.setShotId(201L);
        generation.setUserId(7L);
        generation.setGenTaskId(500L);
        generation.setVideoKey("users/7/videos/1.mp4");
        DramaShotGeneration uploaded = new DramaShotGeneration();
        uploaded.setShotId(201L);
        uploaded.setUserId(7L);
        uploaded.setVideoKey("users/7/videos/upload.mp4");
        when(shotGenerationMapper.selectList(any())).thenReturn(List.of(generation, uploaded));

        service.deleteShot(201L);

        verify(creationService).removeDramaOutputs(7L, 500L, "users/7/videos/1.mp4");
        verify(creationService, org.mockito.Mockito.times(1)).removeDramaOutputs(any(), any(), any());
    }

    @Test
    void deleteShotRejectsWhileGenerating() {
        when(episodeMapper.selectById(31L)).thenReturn(ownedEpisode());
        storedChapters.add(pendingChapter());
        DramaShot generating = pendingShot();
        generating.setId(201L);
        generating.setShotIndex(1);
        generating.setGenTaskId(500L);
        storedShots.add(generating);
        DramaShot other = pendingShot();
        other.setId(202L);
        other.setShotIndex(2);
        storedShots.add(other);
        GenTask running = new GenTask();
        running.setId(500L);
        running.setStatus(TaskStatus.RUNNING);
        when(genTaskMapper.selectById(500L)).thenReturn(running);

        assertEquals("该镜正在生成，请等任务结束再删除",
                assertThrows(BizException.class, () -> service.deleteShot(201L)).getMessage());
        verify(filmService, never()).discardAssembledFilm(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void isActiveGenerateTaskIgnoresSucceeded() {
        GenTask succeeded = new GenTask();
        succeeded.setStatus(TaskStatus.SUCCEEDED);
        assertFalse(DramaStoryboardService.isActiveGenerateTask(succeeded));

        GenTask running = new GenTask();
        running.setStatus(TaskStatus.RUNNING);
        assertTrue(DramaStoryboardService.isActiveGenerateTask(running));

        GenTask pending = new GenTask();
        pending.setStatus(TaskStatus.PENDING);
        assertTrue(DramaStoryboardService.isActiveGenerateTask(pending));

        GenTask failed = new GenTask();
        failed.setStatus(TaskStatus.FAILED);
        assertFalse(DramaStoryboardService.isActiveGenerateTask(failed));
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
        assertTrue(resp.getCompiledPrompt().contains("动作：中景。夏安往白板贴照片。"));
        assertTrue(resp.getCompiledPrompt().contains("场景：幻梦侦探社 日 内"));
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
        assertEquals(List.of(1L), resp.getReferenceAssetIds());
        assertEquals("洛白", resp.getReferences().get(0).getName());
        assertEquals(1, resp.getReferences().size());
    }

    @Test
    void compileKeepsUserCharacterListWithoutReinjectingMentions() {
        DramaChapter chapter = pendingChapter();
        storedChapters.add(chapter);
        DramaShot shot = pendingShot();
        shot.setChapterId(chapter.getId());
        shot.setCharacterNames(List.of("局长"));
        shot.setNarrativeGoal("全景。风扇转动，夏安贴照片，局长摔档案。");
        shot.setContinuityOut("洛白拿出怀表");
        shot.setDialogue("局长：洛老弟，别睡了。");
        storedShots.add(shot);
        when(episodeMapper.selectById(31L)).thenReturn(ownedEpisode());
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER),
                lockedAsset(2L, "夏安", DramaContract.ASSET_TYPE_CHARACTER),
                lockedAsset(3L, "局长", DramaContract.ASSET_TYPE_CHARACTER),
                lockedAsset(5L, "幻梦侦探社", DramaContract.ASSET_TYPE_SCENE)));

        DramaShotResp resp = service.compileShot(201L);

        assertEquals(List.of("局长"), resp.getCharacterNames());
        assertEquals(1, resp.getCastCount());
        assertTrue(resp.getVisiblePrompt().contains("@夏安"));
        assertTrue(resp.getVisiblePrompt().contains("@局长"));
        assertEquals(List.of("幻梦侦探社", "夏安", "局长"),
                resp.getReferences().stream().map(ref -> ref.getName()).toList());
        assertTrue(resp.getCompiledPrompt().contains("本镜恰好 1 人"));
    }

    @Test
    void compileDropsDeletedAssetMentionAndKeepsRemainingCharacters() {
        DramaChapter chapter = pendingChapter();
        storedChapters.add(chapter);
        DramaShot shot = pendingShot();
        shot.setChapterId(chapter.getId());
        shot.setCharacterNames(List.of("洛白", "夏安"));
        shot.setPropNames(List.of("白板"));
        shot.setVisiblePrompt("场景：侦探社\n镜头1：@洛白 站在@白板前，@夏安 抬头。");
        shot.setNarrativeGoal("入梦");
        DramaShotBeats beats = new DramaShotBeats();
        beats.setBlocking("洛白站在白板前");
        DramaShotBeats.Beat beat = new DramaShotBeats.Beat();
        beat.setCamera("近景");
        beat.setAction("夏安抬头看白板。");
        beats.setBeats(List.of(beat));
        shot.setShotBeats(beats);
        storedShots.add(shot);
        when(episodeMapper.selectById(31L)).thenReturn(ownedEpisode());
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER),
                pendingAsset(2L, "夏安", DramaContract.ASSET_TYPE_CHARACTER)));

        DramaShotResp resp = service.compileShot(201L);

        assertTrue(resp.getVisiblePrompt().contains("@洛白"));
        assertTrue(resp.getVisiblePrompt().contains("@夏安"));
        assertTrue(resp.getVisiblePrompt().contains("白板"));
        assertFalse(resp.getVisiblePrompt().contains("@白板"));
        assertEquals(List.of("洛白"),
                resp.getReferences().stream().map(ref -> ref.getName()).toList());
    }

    @Test
    void compileCitesPropsUsedInActionAndListedOnTheShot() {
        DramaChapter chapter = pendingChapter();
        storedChapters.add(chapter);
        DramaShot shot = pendingShot();
        shot.setChapterId(chapter.getId());
        shot.setCharacterNames(List.of("洛白"));
        shot.setPropNames(List.of("怀表", "手电筒", "没有图的杯子"));
        shot.setNarrativeGoal("入梦");
        DramaShotBeats beats = new DramaShotBeats();
        beats.setBlocking("洛白在病床旁");
        DramaShotBeats.Beat beat = new DramaShotBeats.Beat();
        beat.setCamera("近景");
        beat.setAction("洛白按下怀表开关，又瞥见档案袋。");
        beat.setContinuity("接松手");
        beat.setDialogue("洛白：杯子在桌上。");
        beats.setBeats(List.of(beat));
        shot.setShotBeats(beats);
        storedShots.add(shot);
        when(episodeMapper.selectById(31L)).thenReturn(ownedEpisode());
        DramaAsset unimaged = pendingAsset(9L, "没有图的杯子", DramaContract.ASSET_TYPE_PROP);
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER),
                lockedAsset(6L, "怀表", DramaContract.ASSET_TYPE_PROP),
                lockedAsset(7L, "档案袋", DramaContract.ASSET_TYPE_PROP),
                lockedAsset(8L, "手电筒", DramaContract.ASSET_TYPE_PROP),
                unimaged));

        DramaShotResp resp = service.compileShot(201L);

        assertTrue(resp.getVisiblePrompt().contains("站位：@洛白在病床旁，@手电筒，@没有图的杯子。"));
        assertTrue(resp.getVisiblePrompt().contains("@洛白 按下@怀表开关，又瞥见@档案袋，说：“杯子在桌上。”"));
        assertFalse(resp.getVisiblePrompt().contains("说：@"));
        assertEquals(List.of("洛白", "手电筒", "怀表", "档案袋"),
                resp.getReferences().stream().map(ref -> ref.getName()).toList());
    }

    @Test
    void updateShotPersistsRemovedCharacters() {
        DramaChapter chapter = pendingChapter();
        storedChapters.add(chapter);
        DramaShot shot = pendingShot();
        shot.setChapterId(chapter.getId());
        shot.setCharacterNames(List.of("夏安", "赵局长", "局长", "洛白"));
        shot.setDialogue("赵局长：洛老弟，别睡了。");
        shot.setNarrativeGoal("中景。赵局长摔档案。");
        storedShots.add(shot);
        when(episodeMapper.selectById(31L)).thenReturn(ownedEpisode());
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER),
                lockedAsset(2L, "夏安", DramaContract.ASSET_TYPE_CHARACTER),
                lockedAsset(3L, "局长", DramaContract.ASSET_TYPE_CHARACTER),
                lockedAsset(4L, "赵局长", DramaContract.ASSET_TYPE_CHARACTER),
                lockedAsset(5L, "幻梦侦探社", DramaContract.ASSET_TYPE_SCENE)));

        DramaShotSaveReq req = new DramaShotSaveReq();
        req.setCharacterNames(List.of("洛白"));
        DramaShotResp resp = service.updateShot(201L, req);

        assertEquals(List.of("洛白"), resp.getCharacterNames());
        assertEquals(1, resp.getCastCount());
        assertFalse(resp.getCharacterNames().contains("赵局长"));
        assertFalse(resp.getCharacterNames().contains("局长"));
    }

    @Test
    void updateShotClearsLocationNameAndSceneReference() {
        DramaChapter chapter = pendingChapter();
        storedChapters.add(chapter);
        DramaShot shot = pendingShot();
        shot.setChapterId(chapter.getId());
        shot.setCharacterNames(List.of("洛白"));
        shot.setLocationName("梦境");
        shot.setReferenceAssetIds(List.of(1L, 5L));
        storedShots.add(shot);
        when(episodeMapper.selectById(31L)).thenReturn(ownedEpisode());
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER),
                lockedAsset(5L, "梦境", DramaContract.ASSET_TYPE_SCENE)));

        DramaShotSaveReq req = new DramaShotSaveReq();
        req.setLocationName("");
        DramaShotResp resp = service.updateShot(201L, req);

        assertNull(resp.getLocationName());
        assertEquals(List.of("洛白"), resp.getCharacterNames());
        assertEquals(List.of("洛白"),
                resp.getReferences().stream().map(ref -> ref.getName()).toList());
        ArgumentCaptor<DramaShot> captor = ArgumentCaptor.forClass(DramaShot.class);
        verify(shotMapper, org.mockito.Mockito.atLeastOnce()).updateById(captor.capture());
        assertNull(captor.getValue().getLocationName());
    }

    @Test
    void getStoryboardCitesImagedSceneWhenShotLostItsLocation() {
        DramaEpisode episode = ownedEpisode();
        episode.setScript(ScriptDocs.toMap(detectiveDoc()));
        when(episodeMapper.selectById(31L)).thenReturn(episode);
        storedChapters.add(pendingChapter());
        DramaShot shot = pendingShot();
        shot.setLocationName(null);
        shot.setScene("侦探社日间，风扇转动，白板贴照片");
        shot.setVisiblePrompt("场景：侦探社日间，风扇转动，白板贴照片\n镜头1：中景。");
        shot.setReferenceAssetIds(new ArrayList<>(List.of(1L)));
        shot.setCompiledPrompt("old");
        shot.setCompiledFingerprint("old");
        storedShots.add(shot);
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER),
                lockedAsset(5L, "幻梦侦探社", DramaContract.ASSET_TYPE_SCENE)));

        DramaStoryboardResp board = service.getStoryboard(31L);

        DramaShotResp resp = board.getChapters().get(0).getShots().get(0);
        assertEquals("幻梦侦探社", resp.getLocationName());
        assertTrue(resp.getVisiblePrompt().startsWith("场景：@幻梦侦探社。侦探社日间，风扇转动，白板贴照片"));
        assertTrue(resp.getReferenceAssetIds().contains(5L));
    }

    @Test
    void saveVisiblePromptKeepsLocationWhenSceneIsNotMentioned() {
        storedChapters.add(pendingChapter());
        DramaShot shot = pendingShot();
        shot.setLocationName("幻梦侦探社");
        storedShots.add(shot);
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER),
                lockedAsset(5L, "幻梦侦探社", DramaContract.ASSET_TYPE_SCENE)));

        DramaShotSaveReq req = new DramaShotSaveReq();
        req.setVisiblePrompt("场景：侦探社日间，风扇转动\n镜头1：中景。@洛白推门。");
        service.updateShot(201L, req);

        assertEquals("幻梦侦探社", shot.getLocationName());
    }

    @Test
    void saveVisiblePromptKeepsDialoguePlainAndClearsLegacyOverride() {
        storedChapters.add(pendingChapter());
        DramaShot shot = pendingShot();
        shot.setPromptOverride("整段旧覆盖，不要拆。");
        shot.setVisiblePrompt(null);
        storedShots.add(shot);
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER),
                lockedAsset(5L, "幻梦侦探社", DramaContract.ASSET_TYPE_SCENE)));

        DramaShotSaveReq req = new DramaShotSaveReq();
        req.setVisiblePrompt("场景：@幻梦侦探社\n镜头1：景别：中景。 动作：@洛白推门。 台词：@洛白：我来了。");
        req.setReferenceAssetIds(List.of(5L, 1L));
        DramaShotResp resp = service.updateShot(201L, req);

        assertNull(shot.getPromptOverride());
        assertFalse(Boolean.TRUE.equals(resp.getLegacyOverride()));
        assertTrue(resp.getVisiblePrompt().contains("台词：洛白：我来了。"));
        assertFalse(resp.getVisiblePrompt().contains("@洛白："));
        assertEquals("洛白：我来了。", resp.getDialogue());
        assertEquals(List.of(5L, 1L), resp.getReferenceAssetIds());
        assertTrue(resp.getCompiledPrompt().contains("洛白（<Picture 2>）"));
        int dialogueAt = resp.getCompiledPrompt().indexOf("台词：洛白：我来了。");
        assertTrue(dialogueAt >= 0);
        int nextLine = resp.getCompiledPrompt().indexOf('\n', dialogueAt);
        String clause = nextLine < 0
                ? resp.getCompiledPrompt().substring(dialogueAt)
                : resp.getCompiledPrompt().substring(dialogueAt, nextLine);
        assertFalse(clause.contains("<Picture"));
        assertFalse(clause.contains("@"));
    }

    @Test
    void legacyOverrideSubmitsRawPromptUntilEdited() {
        storedChapters.add(pendingChapter());
        DramaShot shot = pendingShot();
        shot.setChapterId(pendingChapter().getId());
        shot.setPromptOverride("整段旧覆盖，不要拆。");
        shot.setReferenceAssetIds(List.of(1L));
        storedShots.add(shot);
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER)));
        DramaSeries series = ownedSeries();
        series.setVideoModelId(88L);
        when(seriesMapper.selectById(21L)).thenReturn(series);
        when(aiModelMapper.selectById(88L)).thenReturn(seedanceModel());
        GenerateTaskResp task = new GenerateTaskResp();
        task.setId(500L);
        task.setStatus("PENDING");
        when(generateService.submit(any(GenerateSubmitReq.class), any())).thenReturn(task);

        service.generateShot(201L);

        ArgumentCaptor<GenerateSubmitReq> reqCaptor = ArgumentCaptor.forClass(GenerateSubmitReq.class);
        verify(generateService).submit(reqCaptor.capture(), any());
        assertEquals("整段旧覆盖，不要拆。", reqCaptor.getValue().getPrompt());
        org.junit.jupiter.api.Assertions.assertNull(reqCaptor.getValue().getFrames());
        assertEquals("users/7/assets/1.png", reqCaptor.getValue().getReferences().get(0).getStorageKey());

        DramaShotSaveReq edit = new DramaShotSaveReq();
        edit.setVisiblePrompt("动作：@洛白回头。");
        edit.setReferenceAssetIds(List.of(1L));
        service.updateShot(201L, edit);
        service.generateShot(201L);

        verify(generateService, org.mockito.Mockito.times(2)).submit(reqCaptor.capture(), any());
        String edited = reqCaptor.getAllValues().get(reqCaptor.getAllValues().size() - 1).getPrompt();
        assertTrue(edited.contains("洛白（<Picture 1>）"));
        assertFalse(edited.contains("整段旧覆盖"));
        assertNull(shot.getPromptOverride());
    }

    @Test
    void generateRejectsWhenImageReferencesExceedModelCap() {
        storedChapters.add(pendingChapter());
        DramaShot shot = pendingShot();
        shot.setChapterId(pendingChapter().getId());
        StringBuilder visible = new StringBuilder("动作：");
        java.util.List<DramaAsset> assets = new java.util.ArrayList<>();
        java.util.List<Long> ids = new java.util.ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            assets.add(lockedAsset((long) i, "角色" + i, DramaContract.ASSET_TYPE_CHARACTER));
            ids.add((long) i);
            visible.append("@角色").append(i).append("出场。");
        }
        shot.setVisiblePrompt(visible.toString());
        shot.setReferenceAssetIds(ids);
        storedShots.add(shot);
        when(assetMapper.selectList(any())).thenReturn(assets);
        DramaSeries series = ownedSeries();
        series.setVideoModelId(88L);
        when(seriesMapper.selectById(21L)).thenReturn(series);
        AiModel model = new AiModel();
        model.setId(88L);
        model.setName("Agnes Flash");
        model.setCategory("video");
        model.setEnabled(true);
        model.setAdapterKey("agnes-video-2.5");
        model.setProvider("官方API");
        model.setModelKey("agnes-video-2.5-flash");
        model.setCapabilities(java.util.List.of("video", "t2v", "r2v"));
        when(aiModelMapper.selectById(88L)).thenReturn(model);

        BizException error = assertThrows(BizException.class, () -> service.generateShot(201L));
        assertEquals("当前模型最多支持 5 张", error.getMessage());
    }

    @Test
    void compileOverwritesVisiblePromptAndClearsOverride() {
        storedChapters.add(pendingChapter());
        DramaShot shot = pendingShot();
        shot.setVisiblePrompt("手改动作：@洛白不动。");
        shot.setPromptOverride("旧覆盖");
        storedShots.add(shot);
        when(episodeMapper.selectById(31L)).thenReturn(ownedEpisode());
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER),
                lockedAsset(5L, "幻梦侦探社", DramaContract.ASSET_TYPE_SCENE)));

        DramaShotResp resp = service.compileShot(201L);

        assertNull(shot.getPromptOverride());
        assertFalse(resp.getVisiblePrompt().contains("手改动作"));
        assertTrue(resp.getVisiblePrompt().contains("@洛白"));
        assertTrue(resp.getCompiledPrompt().contains("<Picture"));
        assertFalse(Boolean.TRUE.equals(resp.getLegacyOverride()));
    }

    private void stubLlmDrafts() {
        DramaTextService llm = org.mockito.Mockito.mock(DramaTextService.class);
        when(textService.getIfAvailable()).thenReturn(llm);
        when(llm.draftShots(org.mockito.ArgumentMatchers.anyLong(), any(ScriptDoc.class), any(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any()))
                .thenAnswer(invocation -> DramaShotDrafts.fromScript(
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        invocation.getArgument(3)));
    }

    private static DramaEpisode ownedEpisode() {
        DramaEpisode episode = new DramaEpisode();
        episode.setId(31L);
        episode.setUserId(7L);
        episode.setSeriesId(21L);
        episode.setEpisodeNumber(1);
        episode.setName("第 1 集");
        episode.setStatus(DramaContract.STATUS_LOCKED);
        episode.setEngine(DramaContract.ENGINE_PENDING);
        return episode;
    }

    @Test
    void generateShotSubmitsUserFramesWhenModeIsI2v() {
        DramaChapter chapter = pendingChapter();
        storedChapters.add(chapter);
        DramaShot shot = pendingShot();
        shot.setChapterId(chapter.getId());
        shot.setVideoGenerateMode(DramaVideoGenerateModes.I2V);
        shot.setFirstFrameKey("users/7/drama/21/frames/open.webp");
        shot.setEndFrameKey("users/7/drama/21/frames/end.webp");
        storedShots.add(shot);
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER)));
        DramaSeries series = ownedSeries();
        series.setVideoModelId(88L);
        series.setVideoGenerateMode(DramaVideoGenerateModes.R2V);
        series.setAspectRatio("9:16");
        when(seriesMapper.selectById(21L)).thenReturn(series);
        when(aiModelMapper.selectById(88L)).thenReturn(r2vModel());
        GenerateTaskResp task = new GenerateTaskResp();
        task.setId(500L);
        task.setStatus("PENDING");
        when(generateService.submit(any(GenerateSubmitReq.class), any())).thenReturn(task);

        service.generateShot(201L);

        ArgumentCaptor<GenerateSubmitReq> reqCaptor = ArgumentCaptor.forClass(GenerateSubmitReq.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> extraCaptor = ArgumentCaptor.forClass(java.util.Map.class);
        verify(generateService).submit(reqCaptor.capture(), extraCaptor.capture());
        assertEquals("users/7/drama/21/frames/open.webp", reqCaptor.getValue().getFrames().getFirstFrameKey());
        assertEquals("users/7/drama/21/frames/end.webp", reqCaptor.getValue().getFrames().getLastFrameKey());
        assertNull(reqCaptor.getValue().getReferences());
        assertEquals(DramaVideoGenerateModes.I2V, extraCaptor.getValue().get("videoGenerateMode"));
        assertEquals(DramaVideoGenerateModes.I2V, shot.getVideoGenerateMode());
    }

    @Test
    void generateShotForBatchStillForcesR2v() {
        DramaChapter chapter = pendingChapter();
        storedChapters.add(chapter);
        DramaShot shot = asI2v(pendingShot());
        shot.setChapterId(chapter.getId());
        shot.setFirstFrameKey("users/7/drama/21/frames/open.webp");
        storedShots.add(shot);
        when(assetMapper.selectList(any())).thenReturn(List.of(
                lockedAsset(1L, "洛白", DramaContract.ASSET_TYPE_CHARACTER),
                lockedAsset(5L, "幻梦侦探社", DramaContract.ASSET_TYPE_SCENE)));
        DramaSeries series = ownedSeries();
        series.setVideoModelId(88L);
        series.setAspectRatio("16:9");
        when(seriesMapper.selectById(21L)).thenReturn(series);
        when(aiModelMapper.selectById(88L)).thenReturn(r2vModel());
        GenerateTaskResp task = new GenerateTaskResp();
        task.setId(501L);
        task.setStatus("PENDING");
        when(generateService.submit(any(GenerateSubmitReq.class), any())).thenReturn(task);

        service.generateShotForBatch(201L);

        ArgumentCaptor<GenerateSubmitReq> reqCaptor = ArgumentCaptor.forClass(GenerateSubmitReq.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> extraCaptor = ArgumentCaptor.forClass(java.util.Map.class);
        verify(generateService).submit(reqCaptor.capture(), extraCaptor.capture());
        assertNull(reqCaptor.getValue().getFrames());
        assertTrue(reqCaptor.getValue().getReferences() != null && !reqCaptor.getValue().getReferences().isEmpty());
        assertEquals(DramaVideoGenerateModes.R2V, extraCaptor.getValue().get("videoGenerateMode"));
        assertEquals(DramaVideoGenerateModes.R2V, shot.getVideoGenerateMode());
    }

    @Test
    void usePrevEndFrameCopiesUserEndFrameNotVideoTail() {
        DramaChapter chapter = pendingChapter();
        storedChapters.add(chapter);
        DramaShot first = pendingShot();
        first.setId(200L);
        first.setShotIndex(1);
        first.setChapterId(chapter.getId());
        first.setVideoTailKey("users/7/gen/shot1-tail.webp");
        first.setEndFrameKey("users/7/drama/21/frames/user-end.webp");
        first.setEndFrameOrigin("upload");
        storedShots.add(first);
        DramaShot second = pendingShot();
        second.setId(201L);
        second.setShotIndex(2);
        second.setChapterId(chapter.getId());
        storedShots.add(second);
        when(assetMapper.selectList(any())).thenReturn(List.of());

        DramaShotResp resp = service.usePrevEndFrame(201L);

        assertEquals("users/7/drama/21/frames/user-end.webp", second.getFirstFrameKey());
        assertEquals("prev-end", second.getFirstFrameOrigin());
        assertEquals("prev-end", resp.getFirstFrameSource());
        assertEquals("users/7/drama/21/frames/user-end.webp", resp.getFirstFrameKey());
    }

    @Test
    void usePrevEndFrameFallsBackToVideoTail() {
        DramaChapter chapter = pendingChapter();
        storedChapters.add(chapter);
        DramaShot first = pendingShot();
        first.setId(200L);
        first.setShotIndex(1);
        first.setChapterId(chapter.getId());
        first.setVideoTailKey("users/7/gen/shot1-tail.webp");
        storedShots.add(first);
        DramaShot second = pendingShot();
        second.setId(201L);
        second.setShotIndex(2);
        second.setChapterId(chapter.getId());
        storedShots.add(second);
        when(assetMapper.selectList(any())).thenReturn(List.of());

        DramaShotResp resp = service.usePrevEndFrame(201L);

        assertEquals("users/7/gen/shot1-tail.webp", second.getFirstFrameKey());
        assertEquals("prev-tail", second.getFirstFrameOrigin());
        assertEquals("prev-tail", resp.getFirstFrameSource());
    }

    @Test
    void usePrevEndFrameCopiesAcrossChapters() {
        DramaChapter firstChapter = pendingChapter();
        DramaChapter secondChapter = pendingChapter();
        secondChapter.setId(91L);
        secondChapter.setChapterIndex(2);
        storedChapters.add(firstChapter);
        storedChapters.add(secondChapter);
        DramaShot first = pendingShot();
        first.setId(200L);
        first.setShotIndex(1);
        first.setChapterId(firstChapter.getId());
        first.setEndFrameKey("users/7/drama/21/frames/scene-end.webp");
        storedShots.add(first);
        DramaShot second = pendingShot();
        second.setId(201L);
        second.setShotIndex(2);
        second.setChapterId(secondChapter.getId());
        storedShots.add(second);
        when(assetMapper.selectList(any())).thenReturn(List.of());

        DramaShotResp resp = service.usePrevEndFrame(201L);

        assertEquals("users/7/drama/21/frames/scene-end.webp", second.getFirstFrameKey());
        assertEquals("prev-end", resp.getFirstFrameSource());
    }

    @Test
    void usePrevEndFrameRejectsWhenPreviousHasNoFrame() {
        DramaChapter chapter = pendingChapter();
        storedChapters.add(chapter);
        DramaShot first = pendingShot();
        first.setId(200L);
        first.setShotIndex(1);
        first.setChapterId(chapter.getId());
        storedShots.add(first);
        DramaShot second = pendingShot();
        second.setId(201L);
        second.setShotIndex(2);
        second.setChapterId(chapter.getId());
        storedShots.add(second);

        BizException error = assertThrows(BizException.class, () -> service.usePrevEndFrame(201L));
        assertTrue(error.getMessage().contains("尾帧"));
        assertNull(second.getFirstFrameKey());
    }

    @Test
    void useAssetEndFrameCopiesLibraryImage() {
        storedChapters.add(pendingChapter());
        DramaShot shot = pendingShot();
        storedShots.add(shot);
        cn.aigc.dit.server.entity.Asset video = new cn.aigc.dit.server.entity.Asset();
        video.setId(9L);
        video.setKind("video");
        video.setStorageKey("users/7/assets/clip.mp4");
        when(assetService.requireOwned(9L)).thenReturn(video);

        BizException missing = assertThrows(BizException.class, () -> service.useAssetEndFrame(201L, 9L));
        assertTrue(missing.getMessage().contains("图片"));

        cn.aigc.dit.server.entity.Asset image = new cn.aigc.dit.server.entity.Asset();
        image.setId(5L);
        image.setKind("image");
        image.setStorageKey("users/7/assets/5.png");
        when(assetService.requireOwned(5L)).thenReturn(image);

        DramaShotResp resp = service.useAssetEndFrame(201L, 5L);
        assertEquals("users/7/assets/5.png", shot.getEndFrameKey());
        assertEquals("asset", shot.getEndFrameOrigin());
        assertEquals("asset", resp.getEndFrameSource());
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
        shot.setNarrativeGoal("洛白交代空间");
        shot.setCharacterNames(List.of("洛白"));
        shot.setPropNames(List.of());
        shot.setLocationName("幻梦侦探社");
        shot.setCastCount(1);
        shot.setStatus(DramaContract.STATUS_PENDING_CONFIRM);
        shot.setReferenceAssetIds(new ArrayList<>());
        return shot;
    }

    private static DramaShot asI2v(DramaShot shot) {
        shot.setVideoGenerateMode(DramaVideoGenerateModes.I2V);
        shot.setVideoChainPrevTail(false);
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

    private static AiModel imageModel() {
        AiModel model = new AiModel();
        model.setId(77L);
        model.setName("Agnes Image");
        model.setCategory("image");
        model.setEnabled(true);
        model.setAdapterKey("agnes-image-2.5");
        model.setProvider("官方API");
        model.setModelKey("agnes-image-2.5-flash");
        model.setCapabilities(List.of("image", "t2i", "i2i"));
        return model;
    }

    private static AiModel gptImageModel() {
        AiModel model = new AiModel();
        model.setId(77L);
        model.setName("GPT Image 2");
        model.setCategory("image");
        model.setEnabled(true);
        model.setAdapterKey("gpt-image-2");
        model.setProvider("RunningHub");
        model.setModelKey("gpt-image-2");
        model.setCapabilities(List.of("image", "t2i", "i2i"));
        return model;
    }

    /** 无适配协议且 capabilities 仅 t2i：开场静帧应降级文生图。 */
    private static AiModel t2iOnlyImageModel() {
        AiModel model = new AiModel();
        model.setId(77L);
        model.setName("T2I Only");
        model.setCategory("image");
        model.setEnabled(true);
        model.setAdapterKey(null);
        model.setProvider("Custom");
        model.setModelKey("custom-t2i");
        model.setCapabilities(List.of("image", "t2i"));
        return model;
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

    private static AiModel wan3Model() {
        AiModel model = new AiModel();
        model.setId(88L);
        model.setName("Wan 3.0");
        model.setCategory("video");
        model.setEnabled(true);
        model.setAdapterKey("wan3");
        model.setProvider("官方API");
        model.setModelKey("wan3.0-video");
        model.setCapabilities(List.of("video", "t2v", "i2v"));
        return model;
    }
}
