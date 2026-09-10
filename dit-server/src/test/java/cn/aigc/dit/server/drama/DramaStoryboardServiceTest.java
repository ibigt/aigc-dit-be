package cn.aigc.dit.server.drama;

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
import cn.aigc.dit.server.drama.model.resp.DramaShotResp;
import cn.aigc.dit.server.drama.model.resp.DramaStoryboardResp;
import cn.aigc.dit.server.drama.text.DramaTextService;
import cn.aigc.dit.server.drama.text.ScriptDoc;
import cn.aigc.dit.server.drama.text.ScriptDocs;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
    private ObjectProvider<DramaTextService> textService;

    private DramaStoryboardService service;
    private final List<DramaChapter> storedChapters = new ArrayList<>();
    private final List<DramaShot> storedShots = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(100);

    @BeforeEach
    void setUp() {
        service = new DramaStoryboardService(
                seriesMapper, episodeMapper, assetMapper, chapterMapper, shotMapper, textService);
        UserContext.set(new LoginUser(7L, "creator", "c@example.com", "创作者", LoginUser.ROLE_CREATOR));
        storedChapters.clear();
        storedShots.clear();
        org.mockito.Mockito.lenient().when(textService.getIfAvailable()).thenReturn(null);
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
        org.mockito.Mockito.lenient().when(seriesMapper.selectById(21L)).thenReturn(ownedSeries());
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
        assertTrue(first.getCompiledPrompt().startsWith(DramaStyles.require(DramaStyles.ANIME_COMIC).prefix()));
        assertTrue(first.getReferenceAssetIds().contains(1L));
        assertFalse(first.getReferenceAssetIds().contains(3L));
        assertTrue(first.getCompiledPrompt().contains("<Picture 1>"));
        assertEquals(first.getCastCount(), first.getCharacterNames().size());
        assertEquals(1, board.getChapters().size());
        assertTrue(board.getChapters().get(0).getShots().size() >= 4);
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
}
