package cn.aigc.dit.server.service;

import cn.aigc.dit.common.security.LoginUser;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.server.drama.entity.DramaEpisode;
import cn.aigc.dit.server.drama.entity.DramaSeries;
import cn.aigc.dit.server.drama.entity.DramaShot;
import cn.aigc.dit.server.drama.mapper.DramaEpisodeMapper;
import cn.aigc.dit.server.drama.mapper.DramaSeriesMapper;
import cn.aigc.dit.server.drama.mapper.DramaShotMapper;
import cn.aigc.dit.server.entity.AiModel;
import cn.aigc.dit.server.entity.Creation;
import cn.aigc.dit.server.entity.CreationSession;
import cn.aigc.dit.server.entity.GenTask;
import cn.aigc.dit.server.mapper.AiModelMapper;
import cn.aigc.dit.server.mapper.CreationMapper;
import cn.aigc.dit.server.mapper.CreationSessionMapper;
import cn.aigc.dit.server.mapper.GenTaskMapper;
import cn.aigc.dit.server.model.req.CreationSessionRenameReq;
import cn.aigc.dit.server.model.resp.CreationItemResp;
import cn.aigc.dit.server.model.resp.CreationSessionResp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreationServiceTest {

    @Mock
    private CreationSessionMapper sessionMapper;
    @Mock
    private GenTaskMapper genTaskMapper;
    @Mock
    private AiModelMapper aiModelMapper;
    @Mock
    private AssetService assetService;
    @Mock
    private DramaSeriesMapper dramaSeriesMapper;
    @Mock
    private DramaEpisodeMapper dramaEpisodeMapper;
    @Mock
    private DramaShotMapper dramaShotMapper;
    @Mock
    private CreationMapper creationMapper;
    @Mock
    private MediaRetentionService mediaRetention;

    private CreationService service;

    @BeforeEach
    void setUp() {
        service = new CreationService(sessionMapper, genTaskMapper, aiModelMapper, assetService,
                dramaSeriesMapper, dramaEpisodeMapper, dramaShotMapper, mediaRetention);
        ReflectionTestUtils.setField(service, "baseMapper", creationMapper);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void sessionWithNullModelIdDoesNotCrash() {
        CreationSession session = new CreationSession();
        session.setId(9L);
        session.setUserId(1L);
        session.setTitle("会话");
        when(creationMapper.selectList(any())).thenReturn(List.of());
        GenTask orphan = new GenTask();
        orphan.setId(66L);
        orphan.setUserId(1L);
        orphan.setModelId(null);
        orphan.setPrompt("test");
        orphan.setParams(Map.of("sessionId", 9));
        when(genTaskMapper.selectList(any())).thenReturn(List.of(orphan));

        CreationSessionResp resp = ReflectionTestUtils.invokeMethod(service, "toSessionResp", session, true);

        assertEquals(1, resp.getTasks().size());
        assertNull(resp.getTasks().get(0).getModelId());
    }

    @Test
    void modelNameOfIgnoresNullKeyOnImmutableMap() {
        assertNull(CreationService.modelNameOf(Map.of(), null));
        assertNull(CreationService.modelNameOf(Map.of(3L, "Seedance"), null));
        assertEquals("Seedance", CreationService.modelNameOf(Map.of(3L, "Seedance"), 3L));
    }

    @Test
    void fileFormatDerivesFromStorageKey() {
        assertEquals("WAV", CreationService.fileFormat("users/1/voice.wav"));
        assertEquals("MP3", CreationService.fileFormat("users/1/voice.mp3"));
        assertNull(CreationService.fileFormat("users/1/without-extension"));
        assertNull(CreationService.fileFormat(""));
        assertNull(CreationService.fileFormat(null));
    }

    @Test
    void titleFromPromptStripsPictureAnchors() {
        assertEquals("高精度二次元写实，半写实", CreationService.titleFromPrompt(
                "<Picture 1> <Picture 2> <Picture 3> 高精度二次元写实，半写实3D渲染"));
        assertEquals("未命名创作", CreationService.titleFromPrompt("<Picture 1> <Picture 2>"));
        assertEquals("雨后城市", CreationService.titleFromPrompt("雨后城市"));
    }

    @Test
    void projectFilterParsesKindAndId() {
        CreationService.ProjectFilter all = CreationService.ProjectFilter.parse(null);
        CreationService.ProjectFilter drama = CreationService.ProjectFilter.parse("drama:18");
        CreationService.ProjectFilter quick = CreationService.ProjectFilter.parse("quick");
        CreationService.ProjectFilter dramaKind = CreationService.ProjectFilter.parse("drama");
        CreationService.ProjectFilter none = CreationService.ProjectFilter.parse("none");
        CreationService.ProjectFilter invalid = CreationService.ProjectFilter.parse("workflow");

        assertEquals(true, all.all());
        assertEquals("drama", drama.kind());
        assertEquals(18L, drama.id());
        assertEquals("quick", quick.kind());
        assertNull(quick.id());
        assertEquals(false, quick.all());
        assertEquals("drama", dramaKind.kind());
        assertNull(dramaKind.id());
        assertEquals(false, dramaKind.all());
        assertEquals(true, none.none());
        assertEquals(true, invalid.all());
    }

    @Test
    void renameSessionWritesTitle() {
        UserContext.set(new LoginUser(1L, "creator", "c@example.com", "创作者", LoginUser.ROLE_CREATOR));
        CreationSession session = new CreationSession();
        session.setId(9L);
        session.setUserId(1L);
        session.setTitle("旧标题");
        when(sessionMapper.selectById(9L)).thenReturn(session);
        CreationSessionRenameReq req = new CreationSessionRenameReq();
        req.setTitle("  新标题  ");

        CreationSessionResp resp = service.renameSession(9L, req);

        assertEquals("新标题", resp.getTitle());
        assertEquals("新标题", session.getTitle());
        verify(sessionMapper).updateById(session);
    }

    @Test
    void deleteMineRemovesOnlyTheCreationRow() {
        UserContext.set(new LoginUser(1L, "creator", "c@example.com", "创作者", LoginUser.ROLE_CREATOR));
        Creation creation = new Creation();
        creation.setId(3L);
        creation.setUserId(1L);
        creation.setTaskId(9L);
        creation.setStorageKey("users/1/drama/a.png");
        when(creationMapper.selectById(3L)).thenReturn(creation);

        service.deleteMine(3L);

        verify(creationMapper).deleteById(3L);
        verify(mediaRetention).purgeAfterCommit(1L, java.util.List.of("users/1/drama/a.png"));
        verifyNoInteractions(assetService, dramaSeriesMapper);
    }

    @Test
    void removeDramaOutputsMatchesTaskAndFile() {
        service.removeDramaOutputs(null, 9L, "users/1/a.png");
        service.removeDramaOutputs(1L, null, "users/1/a.png");
        service.removeDramaOutputs(1L, 9L, " ");
        verify(creationMapper, never()).delete(any());

        service.removeDramaOutputs(1L, 9L, "users/1/a.png");
        verify(creationMapper).delete(any());
    }

    @Test
    void applyFilmFactsFillsBlanksAndRefreshOverwritesDuration() {
        DramaSeries series = new DramaSeries();
        series.setVideoModelId(8L);
        series.setAspectRatio(" 9：16 ");
        series.setDefaultResolution(" 1080P ");
        Creation creation = new Creation();

        assertEquals(true, CreationService.applyFilmFacts(creation, series, 24, false));
        assertEquals(8L, creation.getModelId());
        assertEquals("9:16", creation.getRatio());
        assertEquals("1080P", creation.getResolution());
        assertEquals(24, creation.getDurationSec());
        assertEquals(false, CreationService.applyFilmFacts(creation, series, 30, false));
        assertEquals(24, creation.getDurationSec());

        assertEquals(true, CreationService.applyFilmFacts(creation, series, 30, true));
        assertEquals(30, creation.getDurationSec());
        assertEquals(8L, creation.getModelId());
    }

    @Test
    void filmDurationUsesOnlyIncludedShots() {
        DramaEpisode episode = new DramaEpisode();
        episode.setFilmKey("users/1/drama/31/film.mp4");
        episode.setFilmShotIds(List.of(11L));
        DramaShot kept = shot(11L, 12);
        DramaShot other = shot(22L, 5);

        assertEquals(12, CreationService.filmDurationSec(episode, List.of(other, kept)));
    }

    @Test
    void galleryBackfillsBareDramaFilmAndLeavesShotCreations() {
        Creation film = new Creation();
        film.setId(1789L);
        film.setUserId(1L);
        film.setProjectKind("drama");
        film.setProjectId(5L);
        film.setCategory("video");
        film.setStorageKey("users/1/drama/31/film.mp4");
        film.setTitle("韩剧柔光风-test · 第 1 集 · 成片");
        film.setPrompt(film.getTitle());

        Creation shotCreation = new Creation();
        shotCreation.setId(1L);
        shotCreation.setUserId(1L);
        shotCreation.setTaskId(9L);
        shotCreation.setModelId(3L);
        shotCreation.setProjectKind("drama");
        shotCreation.setProjectId(5L);
        shotCreation.setCategory("video");
        shotCreation.setStorageKey("users/1/drama/shot.mp4");
        shotCreation.setTitle("分镜");
        shotCreation.setRatio("16:9");
        shotCreation.setResolution("720P");
        shotCreation.setDurationSec(12);

        DramaSeries series = new DramaSeries();
        series.setId(5L);
        series.setName("韩剧柔光风-test");
        series.setVideoModelId(8L);
        series.setAspectRatio("16:9");
        series.setDefaultResolution("720P");

        DramaEpisode episode = new DramaEpisode();
        episode.setId(31L);
        episode.setFilmKey(film.getStorageKey());
        episode.setFilmShotIds(List.of(11L, 22L));
        episode.setFilmFingerprint("smooth:abc");

        AiModel filmModel = new AiModel();
        filmModel.setId(8L);
        filmModel.setName("Seedance");
        AiModel shotModel = new AiModel();
        shotModel.setId(3L);
        shotModel.setName("海螺");

        when(dramaSeriesMapper.selectBatchIds(any())).thenReturn(List.of(series));
        when(dramaEpisodeMapper.selectList(any())).thenReturn(List.of(episode));
        when(dramaShotMapper.selectList(any())).thenReturn(List.of(shot(11L, 5), shot(22L, 5)));
        when(aiModelMapper.selectBatchIds(any())).thenReturn(List.of(filmModel, shotModel));
        when(genTaskMapper.selectBatchIds(any())).thenReturn(List.of());

        List<CreationItemResp> resp = ReflectionTestUtils.invokeMethod(service, "toGallery", List.of(film, shotCreation));

        assertEquals("Seedance", resp.get(0).getModelName());
        assertEquals(8L, resp.get(0).getModelId());
        assertEquals("16:9", resp.get(0).getRatio());
        assertEquals("720P", resp.get(0).getResolution());
        assertEquals(10, resp.get(0).getDurationSec());
        assertEquals("海螺", resp.get(1).getModelName());
        assertEquals(12, resp.get(1).getDurationSec());
        verify(creationMapper).updateById(org.mockito.ArgumentMatchers.<Creation>argThat(item ->
                Long.valueOf(1789L).equals(item.getId()) && Integer.valueOf(10).equals(item.getDurationSec())));
        verify(creationMapper, never()).updateById(org.mockito.ArgumentMatchers.<Creation>argThat(item ->
                Long.valueOf(1L).equals(item.getId())));
    }

    private static DramaShot shot(long id, int durationSec) {
        DramaShot shot = new DramaShot();
        shot.setId(id);
        shot.setEpisodeId(31L);
        shot.setDurationSec(durationSec);
        shot.setVideoKey("users/1/" + id + ".mp4");
        return shot;
    }
}
