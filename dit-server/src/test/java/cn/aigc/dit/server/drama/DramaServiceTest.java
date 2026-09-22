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
import cn.aigc.dit.server.drama.model.req.DramaEpisodeUpdateReq;
import cn.aigc.dit.server.drama.model.req.DramaImageModelBindReq;
import cn.aigc.dit.server.drama.model.req.DramaSeriesCreateReq;
import cn.aigc.dit.server.drama.model.req.DramaSeriesUpdateReq;
import cn.aigc.dit.server.drama.model.req.DramaVideoModelBindReq;
import cn.aigc.dit.server.drama.model.resp.DramaSeriesResp;
import cn.aigc.dit.server.drama.text.DramaTextService;
import cn.aigc.dit.server.drama.text.TextChatGateway;
import cn.aigc.dit.server.entity.AiModel;
import cn.aigc.dit.server.mapper.AiModelMapper;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DramaServiceTest {

    @Mock
    private DramaSeriesMapper seriesMapper;
    @Mock
    private DramaEpisodeMapper episodeMapper;
    @Mock
    private DramaAssetMapper assetMapper;
    @Mock
    private AiModelMapper aiModelMapper;
    @Mock
    private ObjectProvider<DramaTextService> textService;
    @Mock
    private TextChatGateway textChatGateway;
    @Mock
    private DramaStoryboardService storyboardService;
    @Mock
    private DramaFilmService filmService;

    private DramaService service;

    @BeforeEach
    void setUp() {
        service = new DramaService(seriesMapper, episodeMapper, assetMapper, aiModelMapper, textService, textChatGateway, storyboardService, filmService);
        org.mockito.Mockito.lenient().when(filmService.preview(any())).thenReturn(new cn.aigc.dit.server.drama.model.resp.DramaFilmResp());
        UserContext.set(new LoginUser(7L, "creator", "c@example.com", "创作者", LoginUser.ROLE_CREATOR));
        org.mockito.Mockito.lenient().when(aiModelMapper.selectCount(any())).thenReturn(0L);
        org.mockito.Mockito.lenient().when(assetMapper.selectList(any())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void createSeriesInsertsEpisodeOneAsNotStarted() {
        when(seriesMapper.insert(any(DramaSeries.class))).thenAnswer(invocation -> {
            DramaSeries series = invocation.getArgument(0);
            series.setId(21L);
            return 1;
        });
        when(episodeMapper.insert(any(DramaEpisode.class))).thenAnswer(invocation -> {
            DramaEpisode episode = invocation.getArgument(0);
            episode.setId(31L);
            return 1;
        });

        DramaSeriesCreateReq req = new DramaSeriesCreateReq();
        req.setName(" 侦探社 ");
        req.setPlannedEpisodeCount(5);

        DramaSeriesResp resp = service.createSeries(req);
        assertEquals(21L, resp.getId());
        assertEquals("侦探社", resp.getName());
        assertEquals(DramaStyles.ANIME_COMIC, resp.getStyleKey());
        assertEquals("二次元漫画风", resp.getStyleName());
        assertEquals("/drama/styles/anime-comic.jpg", resp.getStyleCover());
        assertNull(resp.getCoverUrl());
        assertEquals("16:9", resp.getAspectRatio());
        assertEquals("720P", resp.getDefaultResolution());
        assertEquals(15, resp.getDefaultDurationSec());
        assertEquals(5, resp.getPlannedEpisodeCount());
        assertEquals(1, resp.getEpisodeCount());
        assertEquals("第 1 集", resp.getEpisodes().get(0).getName());
        assertEquals(DramaContract.STATUS_NOT_STARTED, resp.getEpisodes().get(0).getStatus());

        ArgumentCaptor<DramaSeries> seriesCaptor = ArgumentCaptor.forClass(DramaSeries.class);
        verify(seriesMapper).insert(seriesCaptor.capture());
        assertEquals(7L, seriesCaptor.getValue().getUserId());
        assertEquals(DramaStyles.ANIME_COMIC, seriesCaptor.getValue().getStyleKey());
        assertEquals(DramaStyles.require(DramaStyles.ANIME_COMIC).prefix(), seriesCaptor.getValue().getStylePrefix());
        assertEquals(DramaVideoGenerateModes.R2V, seriesCaptor.getValue().getVideoGenerateMode());
        assertEquals(Boolean.FALSE, seriesCaptor.getValue().getVideoChainPrevTail());
        assertEquals(DramaVideoGenerateModes.R2V, resp.getVideoGenerateMode());
        assertEquals(Boolean.FALSE, resp.getVideoChainPrevTail());
    }

    @Test
    void createSeriesRejectsUnknownStyleKey() {
        DramaSeriesCreateReq req = new DramaSeriesCreateReq();
        req.setName("侦探社");
        req.setStyleKey("cinematic");
        BizException error = assertThrows(BizException.class, () -> service.createSeries(req));
        assertEquals("暂不支持该视觉风格", error.getMessage());
    }

    @Test
    void createSeriesRejectsFourSecondDuration() {
        DramaSeriesCreateReq req = new DramaSeriesCreateReq();
        req.setName("侦探社");
        req.setDefaultDurationSec(4);
        BizException error = assertThrows(BizException.class, () -> service.createSeries(req));
        assertEquals("单镜时长仅支持 5–15 秒", error.getMessage());
    }

    @Test
    void normalizeDurationAcceptsEachSecondFromFiveToFifteen() {
        for (int seconds = 5; seconds <= 15; seconds++) {
            assertEquals(seconds, DramaService.normalizeDuration(seconds));
        }
        BizException tooLong = assertThrows(BizException.class, () -> DramaService.normalizeDuration(16));
        assertEquals("单镜时长仅支持 5–15 秒", tooLong.getMessage());
    }

    @Test
    void updateSeriesNormalizesResolutionAndKeepsCatalogPrefix() {
        DramaSeries existing = new DramaSeries();
        existing.setId(21L);
        existing.setUserId(7L);
        existing.setName("侦探社");
        existing.setStyleKey(DramaStyles.ANIME_COMIC);
        existing.setStylePrefix(DramaStyles.require(DramaStyles.ANIME_COMIC).prefix());
        existing.setAspectRatio("16:9");
        existing.setDefaultResolution("720P");
        existing.setDefaultDurationSec(5);
        existing.setPlannedEpisodeCount(1);
        when(seriesMapper.selectById(21L)).thenReturn(existing);
        when(episodeMapper.selectList(any())).thenReturn(List.of());

        DramaSeriesUpdateReq req = new DramaSeriesUpdateReq();
        req.setDefaultResolution("480p");
        req.setStyleKey(DramaStyles.ANIME_COMIC);
        req.setPlannedEpisodeCount(3);

        DramaSeriesResp resp = service.updateSeries(21L, req);
        assertEquals("480P", existing.getDefaultResolution());
        assertEquals(DramaStyles.require(DramaStyles.ANIME_COMIC).prefix(), existing.getStylePrefix());
        assertEquals(3, existing.getPlannedEpisodeCount());
        assertEquals("二次元漫画风", resp.getStyleName());
        verify(seriesMapper).updateById(existing);
        verify(storyboardService, never()).expireCompiledBySeries(21L);
    }

    @Test
    void updateSeriesExpiresCompiledWhenAspectRatioChanges() {
        DramaSeries existing = new DramaSeries();
        existing.setId(21L);
        existing.setUserId(7L);
        existing.setName("侦探社");
        existing.setStyleKey(DramaStyles.ANIME_COMIC);
        existing.setStylePrefix(DramaStyles.require(DramaStyles.ANIME_COMIC).prefix());
        existing.setAspectRatio("16:9");
        existing.setDefaultResolution("720P");
        existing.setDefaultDurationSec(5);
        existing.setPlannedEpisodeCount(1);
        when(seriesMapper.selectById(21L)).thenReturn(existing);
        when(episodeMapper.selectList(any())).thenReturn(List.of());

        DramaSeriesUpdateReq req = new DramaSeriesUpdateReq();
        req.setAspectRatio("9:16");
        service.updateSeries(21L, req);

        assertEquals("9:16", existing.getAspectRatio());
        verify(storyboardService).expireCompiledBySeries(21L);
    }

    @Test
    void updateSeriesRewritesUnlockedAssetStylePrefix() {
        DramaSeries existing = new DramaSeries();
        existing.setId(21L);
        existing.setUserId(7L);
        existing.setName("侦探社");
        existing.setStyleKey(DramaStyles.ANIME_COMIC);
        existing.setStylePrefix(DramaStyles.require(DramaStyles.ANIME_COMIC).prefix());
        existing.setAspectRatio("16:9");
        existing.setDefaultResolution("720P");
        existing.setDefaultDurationSec(5);
        existing.setPlannedEpisodeCount(1);
        when(seriesMapper.selectById(21L)).thenReturn(existing);
        when(episodeMapper.selectList(any())).thenReturn(List.of());
        DramaAsset unlocked = new DramaAsset();
        unlocked.setId(9L);
        unlocked.setSeriesId(21L);
        unlocked.setStatus(DramaContract.STATUS_PENDING_CONFIRM);
        unlocked.setImagePrompt("洛白角色设定图。" + DramaStyles.require(DramaStyles.ANIME_COMIC).prefix() + "。");
        DramaAsset locked = new DramaAsset();
        locked.setId(10L);
        locked.setSeriesId(21L);
        locked.setStatus(DramaContract.STATUS_LOCKED);
        locked.setImagePrompt("夏安角色设定图。" + DramaStyles.require(DramaStyles.ANIME_COMIC).prefix() + "。");
        when(assetMapper.selectList(any())).thenReturn(List.of(unlocked, locked));

        DramaSeriesUpdateReq req = new DramaSeriesUpdateReq();
        req.setStyleKey(DramaStyles.LIVE_REALISM);
        service.updateSeries(21L, req);

        assertEquals(DramaStyles.LIVE_REALISM, existing.getStyleKey());
        assertTrue(unlocked.getImagePrompt().contains(DramaStyles.require(DramaStyles.LIVE_REALISM).prefix()));
        assertTrue(locked.getImagePrompt().contains(DramaStyles.require(DramaStyles.ANIME_COMIC).prefix()));
        verify(assetMapper).updateById(unlocked);
        verify(assetMapper, never()).updateById(locked);
        verify(storyboardService).expireCompiledBySeries(21L);
    }

    @Test
    void createSeriesRequiresTextModelWhenEnabledExist() {
        when(aiModelMapper.selectCount(any())).thenReturn(1L);
        DramaSeriesCreateReq req = new DramaSeriesCreateReq();
        req.setName("侦探社");
        BizException error = assertThrows(BizException.class, () -> service.createSeries(req));
        assertEquals("请选择文本模型", error.getMessage());
    }

    @Test
    void createSeriesBindsEnabledTextModel() {
        AiModel text = new AiModel();
        text.setId(88L);
        text.setName("Agnes 对话");
        text.setCategory("text");
        text.setEnabled(true);
        text.setAdapterKey("agnes-text");
        text.setProvider("官方API");
        text.setEndpoint("https://api.agnes-ai.cn/v1");
        text.setModelKey("agnes-2.5-flash");
        when(aiModelMapper.selectById(88L)).thenReturn(text);
        when(aiModelMapper.selectBatchIds(any())).thenReturn(List.of(text));
        when(seriesMapper.insert(any(DramaSeries.class))).thenAnswer(invocation -> {
            DramaSeries series = invocation.getArgument(0);
            series.setId(21L);
            return 1;
        });
        when(episodeMapper.insert(any(DramaEpisode.class))).thenAnswer(invocation -> {
            DramaEpisode episode = invocation.getArgument(0);
            episode.setId(31L);
            return 1;
        });

        DramaSeriesCreateReq req = new DramaSeriesCreateReq();
        req.setName("侦探社");
        req.setTextModelId(88L);
        DramaSeriesResp resp = service.createSeries(req);
        assertEquals(88L, resp.getTextModelId());
        assertEquals("Agnes 对话", resp.getTextModelName());
        ArgumentCaptor<DramaSeries> seriesCaptor = ArgumentCaptor.forClass(DramaSeries.class);
        verify(seriesMapper).insert(seriesCaptor.capture());
        assertEquals(88L, seriesCaptor.getValue().getTextModelId());
    }

    @Test
    void listSeriesUsesFirstSceneImageAsCover() {
        DramaSeries series = new DramaSeries();
        series.setId(21L);
        series.setUserId(7L);
        series.setName("侦探社");
        series.setStyleKey(DramaStyles.ANIME_COMIC);
        series.setStylePrefix(DramaStyles.require(DramaStyles.ANIME_COMIC).prefix());
        series.setAspectRatio("16:9");
        series.setDefaultResolution("720P");
        series.setDefaultDurationSec(5);
        series.setPlannedEpisodeCount(1);
        when(seriesMapper.selectList(any())).thenReturn(List.of(series));
        when(episodeMapper.selectList(any())).thenReturn(List.of());
        DramaAsset character = new DramaAsset();
        character.setId(1L);
        character.setSeriesId(21L);
        character.setEpisodeId(31L);
        character.setType(DramaContract.ASSET_TYPE_CHARACTER);
        character.setImageStorageKey("users/7/drama/21/char.png");
        DramaAsset firstScene = new DramaAsset();
        firstScene.setId(2L);
        firstScene.setSeriesId(21L);
        firstScene.setEpisodeId(31L);
        firstScene.setType(DramaContract.ASSET_TYPE_SCENE);
        firstScene.setImageStorageKey("users/7/drama/21/office.png");
        DramaAsset laterScene = new DramaAsset();
        laterScene.setId(3L);
        laterScene.setSeriesId(21L);
        laterScene.setEpisodeId(31L);
        laterScene.setType(DramaContract.ASSET_TYPE_SCENE);
        laterScene.setImageStorageKey("users/7/drama/21/street.png");
        when(assetMapper.selectList(any())).thenReturn(List.of(character, firstScene, laterScene));

        List<DramaSeriesResp> list = service.listSeries();
        assertEquals(1, list.size());
        assertEquals("users/7/drama/21/office.png", list.get(0).getCoverKey());
        assertEquals("/api/media?key=users%2F7%2Fdrama%2F21%2Foffice.png", list.get(0).getCoverUrl());
    }

    @Test
    void deleteSeriesRemovesOwnedProject() {
        DramaSeries existing = new DramaSeries();
        existing.setId(21L);
        existing.setUserId(7L);
        when(seriesMapper.selectById(21L)).thenReturn(existing);
        service.deleteSeries(21L);
        verify(seriesMapper).deleteById(21L);
    }

    @Test
    void deleteSeriesRejectsWhileScriptAnalysisIsActive() {
        DramaSeries existing = new DramaSeries();
        existing.setId(21L);
        existing.setUserId(7L);
        DramaEpisode episode = new DramaEpisode();
        episode.setId(31L);
        episode.setSeriesId(21L);
        episode.setScriptAnalyzingAt(java.time.OffsetDateTime.now());
        when(seriesMapper.selectById(21L)).thenReturn(existing);
        when(episodeMapper.selectList(any())).thenReturn(List.of(episode));

        BizException error = assertThrows(BizException.class, () -> service.deleteSeries(21L));

        assertEquals(409, error.getCode());
        assertEquals("剧本正在分析，暂时无法删除", error.getMessage());
        org.mockito.Mockito.verify(seriesMapper, org.mockito.Mockito.never()).deleteById(21L);
    }

    @Test
    void deleteSeriesHidesOtherUsersProjects() {
        DramaSeries foreign = new DramaSeries();
        foreign.setId(9L);
        foreign.setUserId(99L);
        when(seriesMapper.selectById(9L)).thenReturn(foreign);
        BizException error = assertThrows(BizException.class, () -> service.deleteSeries(9L));
        assertEquals("漫剧项目不存在", error.getMessage());
    }

    @Test
    void getSeriesHidesOtherUsersProjects() {
        DramaSeries foreign = new DramaSeries();
        foreign.setId(9L);
        foreign.setUserId(99L);
        when(seriesMapper.selectById(9L)).thenReturn(foreign);
        BizException error = assertThrows(BizException.class, () -> service.getSeries(9L));
        assertEquals("漫剧项目不存在", error.getMessage());
    }

    @Test
    void updateEpisodeRenamesOwnedEpisode() {
        DramaEpisode episode = new DramaEpisode();
        episode.setId(31L);
        episode.setUserId(7L);
        episode.setSeriesId(21L);
        episode.setEpisodeNumber(1);
        episode.setName("第 1 集");
        episode.setStatus(DramaContract.STATUS_NOT_STARTED);
        when(episodeMapper.selectById(31L)).thenReturn(episode);

        DramaEpisodeUpdateReq req = new DramaEpisodeUpdateReq();
        req.setName(" 开场 ");
        assertEquals("开场", service.updateEpisode(31L, req).getName());
        verify(episodeMapper).updateById(episode);
    }

    @Test
    void bindImageModelWritesIdAndAdapterKey() {
        DramaSeries existing = new DramaSeries();
        existing.setId(21L);
        existing.setUserId(7L);
        existing.setName("侦探社");
        existing.setStyleKey(DramaStyles.ANIME_COMIC);
        existing.setStylePrefix(DramaStyles.require(DramaStyles.ANIME_COMIC).prefix());
        existing.setAspectRatio("16:9");
        existing.setDefaultResolution("720P");
        existing.setDefaultDurationSec(5);
        existing.setPlannedEpisodeCount(1);
        when(seriesMapper.selectById(21L)).thenReturn(existing);
        when(episodeMapper.selectList(any())).thenReturn(List.of());
        AiModel image = new AiModel();
        image.setId(12L);
        image.setName("GPT Image 2");
        image.setCategory("image");
        image.setEnabled(true);
        image.setAdapterKey("gpt-image-2");
        image.setProvider("RunningHub");
        image.setModelKey("gpt-image-2");
        image.setCapabilities(List.of("image", "t2i"));
        when(aiModelMapper.selectById(12L)).thenReturn(image);
        when(aiModelMapper.selectBatchIds(any())).thenReturn(List.of(image));

        DramaImageModelBindReq req = new DramaImageModelBindReq();
        req.setImageModelId(12L);
        DramaSeriesResp resp = service.bindImageModel(21L, req);
        assertEquals(12L, existing.getImageModelId());
        assertEquals("gpt-image-2", existing.getImageAdapterKey());
        assertEquals("GPT Image 2", resp.getImageModelName());
        verify(seriesMapper).updateById(existing);
    }

    @Test
    void bindVideoModelWritesIdAndAdapterKey() {
        DramaSeries existing = new DramaSeries();
        existing.setId(21L);
        existing.setUserId(7L);
        existing.setName("侦探社");
        existing.setStyleKey(DramaStyles.ANIME_COMIC);
        existing.setStylePrefix(DramaStyles.require(DramaStyles.ANIME_COMIC).prefix());
        existing.setAspectRatio("16:9");
        existing.setDefaultResolution("720P");
        existing.setDefaultDurationSec(5);
        existing.setPlannedEpisodeCount(1);
        when(seriesMapper.selectById(21L)).thenReturn(existing);
        when(episodeMapper.selectList(any())).thenReturn(List.of());
        AiModel video = new AiModel();
        video.setId(88L);
        video.setName("MiniMax-H3");
        video.setCategory("video");
        video.setEnabled(true);
        video.setAdapterKey("minimax-h3");
        video.setProvider("ComfyUI");
        video.setModelKey("minimax-h3");
        video.setCapabilities(List.of("video", "t2v", "r2v", "i2v"));
        when(aiModelMapper.selectById(88L)).thenReturn(video);
        when(aiModelMapper.selectBatchIds(any())).thenReturn(List.of(video));

        DramaVideoModelBindReq req = new DramaVideoModelBindReq();
        req.setVideoModelId(88L);
        DramaSeriesResp resp = service.bindVideoModel(21L, req);
        assertEquals(88L, existing.getVideoModelId());
        assertEquals("minimax-h3", existing.getVideoAdapterKey());
        assertEquals("MiniMax-H3", resp.getVideoModelName());
        verify(seriesMapper).updateById(existing);
    }

    @Test
    void bindVideoModelRejectsWithoutR2v() {
        DramaSeries existing = new DramaSeries();
        existing.setId(21L);
        existing.setUserId(7L);
        when(seriesMapper.selectById(21L)).thenReturn(existing);
        AiModel video = new AiModel();
        video.setId(89L);
        video.setName("仅文生视频");
        video.setCategory("video");
        video.setEnabled(true);
        video.setCapabilities(List.of("video", "t2v"));
        when(aiModelMapper.selectById(89L)).thenReturn(video);

        DramaVideoModelBindReq req = new DramaVideoModelBindReq();
        req.setVideoModelId(89L);
        BizException error = assertThrows(BizException.class, () -> service.bindVideoModel(21L, req));
        assertEquals("所选视频模型不支持参考生视频或首尾帧", error.getMessage());
        verify(seriesMapper, never()).updateById(existing);
    }
}
