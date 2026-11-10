package cn.aigc.dit.server.drama;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.server.drama.agent.AgentContext;
import cn.aigc.dit.server.drama.agent.AgentResult;
import cn.aigc.dit.server.drama.agent.DramaAnalysisOrchestrator;
import cn.aigc.dit.server.drama.agent.DramaAnalysisRequest;
import cn.aigc.dit.server.drama.agent.DramaAnalysisResult;
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
import cn.aigc.dit.server.drama.text.AssetDraftList;
import cn.aigc.dit.server.drama.text.ScriptDoc;
import cn.aigc.dit.server.drama.text.ShotDraftList;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DramaAnalysisFacade 测试：把 4 步 Agent 结果翻译回 legacy schema。
 */
class DramaAnalysisFacadeTest {

    private final DramaAnalysisOrchestrator orchestrator = mock(DramaAnalysisOrchestrator.class);
    private final DramaEpisodeMapper episodeMapper = mock(DramaEpisodeMapper.class);
    private final DramaAssetMapper assetMapper = mock(DramaAssetMapper.class);
    private final DramaShotMapper shotMapper = mock(DramaShotMapper.class);
    private final DramaChapterMapper chapterMapper = mock(DramaChapterMapper.class);
    private final DramaSeriesMapper seriesMapper = mock(DramaSeriesMapper.class);

    private final DramaScriptAnalyzeLock analyzeLock = mock(DramaScriptAnalyzeLock.class);

    private final DramaAnalysisFacade facade = new DramaAnalysisFacade(
            orchestrator, episodeMapper, assetMapper, shotMapper, chapterMapper, seriesMapper);

    @org.junit.jupiter.api.BeforeEach
    void injectAnalyzeLock() {
        org.springframework.test.util.ReflectionTestUtils.setField(facade, "analyzeLock", analyzeLock);
    }

    private DramaEpisode episode() {
        DramaEpisode ep = new DramaEpisode();
        ep.setId(100L);
        ep.setSeriesId(1L);
        ep.setUserId(7L);
        ep.setSourceText("原始文本");
        return ep;
    }

    @Test
    void analyzeV2_success_persistsAllOutputs() {
        DramaAnalysisResult result = DramaAnalysisResult.builder()
                .skeleton(new cn.aigc.dit.server.drama.text.SkeletonDoc("", "",
                        List.of(), List.of(), List.of()))
                .script(new ScriptDoc())
                .assets(new AssetDraftList())
                .shots(new ShotDraftList())
                .build();
        when(orchestrator.analyze(any(DramaAnalysisRequest.class))).thenReturn(result);
        when(episodeMapper.selectById(100L)).thenReturn(episode());
        AtomicBoolean heartbeatStopped = new AtomicBoolean();
        when(analyzeLock.startHeartbeat(anyLong())).thenReturn(() -> heartbeatStopped.set(true));

        DramaAnalysisResult out = facade.analyzeV2(episode(), "原始文本", "1", "model");

        assertSame(result, out);
        // 分析锁：占用后必须释放，否则 script_analyzing_at 残留 15 分钟，
        // 前端会一直显示「分析中」、按钮一直禁用
        verify(analyzeLock).claim(100L);
        verify(analyzeLock).release(100L);
        verify(analyzeLock, never()).fail(anyLong(), any());
        // 心跳：慢模型跑一集十几分钟，锁不能因为固定 15 分钟窗口被判过期
        //（过期后页面进度自己清空，用户还能再点一次分析，两次分析写同一集）。
        verify(analyzeLock).startHeartbeat(100L);
        assertTrue(heartbeatStopped.get(), "分析结束必须停掉心跳");
        // 验证 status 落库
        verify(episodeMapper, org.mockito.Mockito.atLeast(1)).updateById(any(DramaEpisode.class));
        // 验证资产/分镜落库
        verify(assetMapper).delete(any(Wrapper.class));
        verify(shotMapper).delete(any(Wrapper.class));
    }

    @Test
    void analyzeV2_failure_stopsHeartbeatAndMarksError() {
        DramaAnalysisResult result = DramaAnalysisResult.builder()
                .failedAt(DramaAnalysisResult.Step.ASSETS)
                .stepError(DramaAnalysisResult.Step.ASSETS, "mock failure")
                .build();
        when(orchestrator.analyze(any(DramaAnalysisRequest.class))).thenReturn(result);
        AtomicBoolean heartbeatStopped = new AtomicBoolean();
        when(analyzeLock.startHeartbeat(anyLong())).thenReturn(() -> heartbeatStopped.set(true));

        BizException ex = assertThrows(BizException.class,
                () -> facade.analyzeV2(episode(), "原始文本", "1", "model"));

        assertTrue(ex.getMessage().contains("资产抽取"), "实际错误：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("mock failure"));
        // 失败要写错误并清掉 analyzing 标记（走分析锁），否则前端既没错误也没进度
        verify(analyzeLock).fail(eq(100L), any());
        verify(analyzeLock, never()).release(anyLong());
        assertTrue(heartbeatStopped.get(), "失败也要停掉心跳，否则锁一直被续期，用户点不了重试");
    }

    @Test
    void analyzeV2_persistsSourceTextBeforePipeline() {
        DramaAnalysisResult result = DramaAnalysisResult.builder()
                .skeleton(new cn.aigc.dit.server.drama.text.SkeletonDoc("", "",
                        List.of(), List.of(), List.of()))
                .script(new ScriptDoc())
                .assets(new AssetDraftList())
                .shots(new ShotDraftList())
                .build();
        when(orchestrator.analyze(any(DramaAnalysisRequest.class))).thenReturn(result);

        facade.analyzeV2(episode(), "  用户输入的剧本  ", "1", "model");

        org.mockito.ArgumentCaptor<DramaEpisode> cap = org.mockito.ArgumentCaptor.forClass(DramaEpisode.class);
        verify(episodeMapper, org.mockito.Mockito.atLeastOnce()).updateById(cap.capture());
        // v1 在 importSource 里把原文存了，v2 之前漏掉：用户输入的剧本只活在浏览器内存里，
        // 页面刷新或切到「资产/分镜」页就没了。下游重跑也要读 episode.source_text。
        assertTrue(cap.getAllValues().stream()
                        .anyMatch(item -> "用户输入的剧本".equals(item.getSourceText())),
                "分析前要把用户输入的原文落库");
    }

    @Test
    void persistScriptV2_setsEngineAndStatus() {
        ScriptDoc doc = new ScriptDoc();
        facade.persistScriptV2(100L, doc);

        org.mockito.ArgumentCaptor<DramaEpisode> cap = org.mockito.ArgumentCaptor.forClass(DramaEpisode.class);
        verify(episodeMapper).updateById(cap.capture());
        DramaEpisode u = cap.getValue();
        assertEquals(100L, u.getId());
        assertEquals(DramaAnalysisFacade.ENGINE_V2, u.getEngine());
        // 必须写「已锁定」：v1 分析完的剧本就是这个状态，资产/分镜门禁全按它判断。
        // 写成 v2 的工作流值 'scripted' 会让分析完的剧集在资产页一直提示
        // 「请先回到剧本页，完成一键分析后再提取」。
        assertEquals(DramaContract.STATUS_LOCKED, u.getStatus());
        assertNotNull(u.getScript());
    }

    @Test
    void persistAssetsV2_insertsAssetsFromDraftList() {
        AssetDraftList list = new AssetDraftList();
        AssetDraftList.AssetDraft a1 = new AssetDraftList.AssetDraft();
        a1.setName("洛白");
        a1.setType("角色");
        a1.setStateTag("base");
        a1.setDescription("短");
        a1.setIdentityInvariants("短");
        a1.setMustSplit(false);
        list.setAssets(List.of(a1));
        when(episodeMapper.selectById(100L)).thenReturn(episode());

        facade.persistAssetsV2(100L, list, null);

        verify(assetMapper).delete(any(Wrapper.class));
        org.mockito.ArgumentCaptor<DramaAsset> cap = org.mockito.ArgumentCaptor.forClass(DramaAsset.class);
        verify(assetMapper).insert(cap.capture());
        DramaAsset row = cap.getValue();
        assertEquals("洛白", row.getName());
        assertEquals("角色", row.getType());
        assertEquals("base", row.getStateTag());
        assertEquals(1L, row.getSeriesId());
        assertEquals(100L, row.getEpisodeId());
        assertEquals(7L, row.getUserId());
        assertFalse(row.getMustSplit());
    }

    @Test
    void persistAssetsV2_backfillsIdentityAndImagePrompt() {
        AssetDraftList list = new AssetDraftList();
        AssetDraftList.AssetDraft a1 = new AssetDraftList.AssetDraft();
        a1.setName("洛白");
        a1.setType("角色");
        a1.setStateTag("base");
        a1.setDescription("20岁男性侦探，黑色碎盖发，穿深灰长风衣");
        a1.setIdentityInvariants("");
        a1.setMustSplit(false);
        list.setAssets(List.of(a1));
        when(episodeMapper.selectById(100L)).thenReturn(episode());

        facade.persistAssetsV2(100L, list, null);

        org.mockito.ArgumentCaptor<DramaAsset> cap = org.mockito.ArgumentCaptor.forClass(DramaAsset.class);
        verify(assetMapper).insert(cap.capture());
        DramaAsset row = cap.getValue();
        assertNotNull(row.getIdentityInvariants(), "identity 必须被兜底补齐");
        assertFalse(row.getIdentityInvariants().isBlank(), "identity 必须被兜底补齐");
        assertNotNull(row.getImagePrompt(), "生图提示词必须被拼接");
        assertFalse(row.getImagePrompt().isBlank(), "生图提示词必须被拼接");
    }

    @Test
    void persistShotsV2_insertsShotsFromDraftList() {
        ShotDraftList list = new ShotDraftList();
        ShotDraftList.ShotDraft s1 = new ShotDraftList.ShotDraft();
        s1.setIndex(1);
        s1.setTitle("开场");
        s1.setDurationSec(8);
        s1.setNarrativeGoal("引子");
        s1.setScene("教室");
        s1.setDialogue("你好");
        s1.setCharacterNames(List.of("洛白"));
        s1.setPropNames(List.of("古铜怀表"));
        s1.setLocationName("教室");
        s1.setCastCount(1);
        list.setShots(List.of(s1));
        when(episodeMapper.selectById(100L)).thenReturn(episode());

        facade.persistShotsV2(100L, list, scriptOfOneScene());

        verify(shotMapper).delete(any(Wrapper.class));
        org.mockito.ArgumentCaptor<DramaShot> cap = org.mockito.ArgumentCaptor.forClass(DramaShot.class);
        verify(shotMapper).insert(cap.capture());
        DramaShot row = cap.getValue();
        assertEquals(1, row.getShotIndex());
        assertEquals("开场", row.getTitle());
        assertEquals(8, row.getDurationSec());
        assertEquals("你好", row.getDialogue());
        assertEquals(List.of("洛白"), row.getCharacterNames());
        assertEquals(1, row.getCastCount());
    }

    /**
     * 回归：drama_shot.chapter_id 是 NOT NULL，v2 流水线没有独立章节步骤，
     * 落库分镜前必须按场次补建章节并回填 chapter_id，
     * 否则插分镜直接报 "null value in column chapter_id"。
     */
    @Test
    void persistShotsV2_createsChapterPerSceneAndFillsChapterId() {
        ShotDraftList list = new ShotDraftList();
        ShotDraftList.ShotDraft s1 = new ShotDraftList.ShotDraft();
        s1.setIndex(1);
        s1.setTitle("开场");
        s1.setChapterIndex(1);
        list.setShots(List.of(s1));
        when(episodeMapper.selectById(100L)).thenReturn(episode());

        org.mockito.ArgumentCaptor<DramaChapter> chapterCap =
                org.mockito.ArgumentCaptor.forClass(DramaChapter.class);
        when(chapterMapper.insert(chapterCap.capture())).thenAnswer(inv -> {
            DramaChapter c = inv.getArgument(0);
            c.setId(555L);
            return 1;
        });

        facade.persistShotsV2(100L, list, scriptOfOneScene());

        // 章节按场次建，且带上了 chapter_index / scene_index
        assertEquals(1, chapterCap.getAllValues().size());
        DramaChapter chapter = chapterCap.getValue();
        assertEquals(100L, chapter.getEpisodeId());
        assertEquals(1, chapter.getChapterIndex());
        assertEquals(1, chapter.getSceneIndex());

        // 分镜回填了 chapter_id
        org.mockito.ArgumentCaptor<DramaShot> shotCap =
                org.mockito.ArgumentCaptor.forClass(DramaShot.class);
        verify(shotMapper).insert(shotCap.capture());
        assertEquals(555L, shotCap.getValue().getChapterId());
    }

    /**
     * 回归：v2 落库必须保留镜头序列、运镜与环境声。
     *
     * <p>丢了 shot_beats，编译器就退回「场景/运镜/动作」三行分支 —— 页面上看不到站位、
     * 镜头N 和环境声，用户反馈的「v2 分镜提示词没之前详细」就是这么来的。
     */
    @Test
    void persistShotsV2_keepsBeatsCameraAndAmbiance() {
        ShotDraftList list = new ShotDraftList();
        ShotDraftList.ShotDraft s1 = new ShotDraftList.ShotDraft();
        s1.setIndex(1);
        s1.setTitle("开场");
        s1.setChapterIndex(1);
        s1.setScene("冷宫内景，金色烛火映着灵位");
        s1.setCamera("中景，跟拍");
        s1.setAmbiance("风声，衣袍摩擦，不要配乐。");

        DramaShotBeats beats = new DramaShotBeats();
        beats.setBlocking("婉儿跪于灵位前，温太医立于门口");
        DramaShotBeats.Beat beat = new DramaShotBeats.Beat();
        beat.setCamera("全景，偏心构图");
        beat.setAction("婉儿抬手擦去灵位上的灰尘。");
        beat.setDialogue("求见。");
        beats.setBeats(List.of(beat));
        s1.setShotBeats(beats);

        list.setShots(List.of(s1));
        when(episodeMapper.selectById(100L)).thenReturn(episode());

        facade.persistShotsV2(100L, list, scriptOfOneScene());

        org.mockito.ArgumentCaptor<DramaShot> cap = org.mockito.ArgumentCaptor.forClass(DramaShot.class);
        verify(shotMapper).insert(cap.capture());
        DramaShot row = cap.getValue();
        assertNotNull(row.getShotBeats(), "镜头序列必须落库");
        assertEquals("婉儿跪于灵位前，温太医立于门口", row.getShotBeats().getBlocking());
        assertEquals(1, row.getShotBeats().getBeats().size());
        assertEquals("中景，跟拍", row.getCameraMotion(), "运镜必须落库");
        assertEquals("风声，衣袍摩擦，不要配乐。", row.getAmbianceAudio(), "环境声必须落库");
        assertEquals("求见。", row.getDialogue(), "镜头台词要汇总到分镜台词");
    }

    /**
     * 回归：模型没给 location_name 时，用所属场次的地点兜底，
     * 否则分镜的 location_name 是空的。
     */
    @Test
    void persistShotsV2_fallsBackToSceneLocation() {
        ShotDraftList list = new ShotDraftList();
        ShotDraftList.ShotDraft s1 = new ShotDraftList.ShotDraft();
        s1.setIndex(1);
        s1.setTitle("开场");
        s1.setChapterIndex(1);
        list.setShots(List.of(s1));
        when(episodeMapper.selectById(100L)).thenReturn(episode());

        ScriptDoc script = scriptOfOneScene();
        script.getScenes().get(0).setLocation("皇后的风仪宫");

        facade.persistShotsV2(100L, list, script);

        org.mockito.ArgumentCaptor<DramaShot> cap = org.mockito.ArgumentCaptor.forClass(DramaShot.class);
        verify(shotMapper).insert(cap.capture());
        assertEquals("皇后的风仪宫", cap.getValue().getLocationName());
    }

    private ScriptDoc scriptOfOneScene() {
        ScriptDoc doc = new ScriptDoc();
        ScriptDoc.Scene scene = new ScriptDoc.Scene();
        scene.setIndex(1);
        scene.setHeading("第一场 教室");
        scene.setCharacters(List.of("洛白"));
        scene.setAction("洛白推门进来。");
        doc.setScenes(List.of(scene));
        return doc;
    }

    /**
     * 回归：shot_index 在本集内必须唯一（uq_drama_shot_episode_index），
     * 而模型给的 index 是「场内序号」——每一场都从 1 开始。
     *
     * <p>之前 v2 照抄模型的 index，第二场的第 1 镜与第一场的第 1 镜同号，
     * 整集落库直接失败（线上 v2 分析就是死在这一步）。
     */
    @Test
    void persistShotsV2_numbersShotIndexPerEpisode() {
        ShotDraftList list = new ShotDraftList();
        // 模型给的是场内序号：两场都是 1、2
        list.setShots(List.of(
                shot(1, 1, "第一场第一镜"),
                shot(1, 2, "第一场第二镜"),
                shot(2, 1, "第二场第一镜"),
                shot(2, 2, "第二场第二镜")));
        when(episodeMapper.selectById(100L)).thenReturn(episode());
        stubChapterIds();

        facade.persistShotsV2(100L, list, scriptOfTwoScenes());

        List<DramaShot> rows = insertedShots(4);
        assertEquals(List.of(1, 2, 3, 4), rows.stream().map(DramaShot::getShotIndex).toList(),
                "shot_index 必须在本集内连续且唯一");
        // 场次归属不能被重编号打乱
        assertEquals(List.of(11L, 11L, 21L, 21L), rows.stream().map(DramaShot::getChapterId).toList());
        assertEquals(List.of("第一场第一镜", "第一场第二镜", "第二场第一镜", "第二场第二镜"),
                rows.stream().map(DramaShot::getTitle).toList());
    }

    /**
     * 回归：模型偶尔不按场次顺序输出分镜。落库序号要按场次排好，
     * 否则前端按 shot_index 排会看到镜头跨场乱序。
     */
    @Test
    void persistShotsV2_ordersShotsByChapter() {
        ShotDraftList list = new ShotDraftList();
        list.setShots(List.of(
                shot(2, 1, "第二场第一镜"),
                shot(1, 1, "第一场第一镜"),
                shot(2, 2, "第二场第二镜"),
                shot(1, 2, "第一场第二镜")));
        when(episodeMapper.selectById(100L)).thenReturn(episode());
        stubChapterIds();

        facade.persistShotsV2(100L, list, scriptOfTwoScenes());

        List<DramaShot> rows = insertedShots(4);
        assertEquals(List.of("第一场第一镜", "第一场第二镜", "第二场第一镜", "第二场第二镜"),
                rows.stream().map(DramaShot::getTitle).toList(),
                "分镜要按场次顺序落库");
        assertEquals(List.of(1, 2, 3, 4), rows.stream().map(DramaShot::getShotIndex).toList());
    }

    /**
     * 回归：duration_sec 是 NOT NULL，title 没上限但不该留空。
     * 模型漏字段时不能把整集落库带崩。
     */
    @Test
    void persistShotsV2_fallsBackToDefaultDurationAndTitle() {
        ShotDraftList list = new ShotDraftList();
        ShotDraftList.ShotDraft s = new ShotDraftList.ShotDraft();
        s.setChapterIndex(1);
        s.setIndex(1);
        list.setShots(List.of(s));
        when(episodeMapper.selectById(100L)).thenReturn(episode());
        stubChapterIds();
        DramaSeries series = new DramaSeries();
        series.setId(1L);
        series.setDefaultDurationSec(5);
        when(seriesMapper.selectById(1L)).thenReturn(series);

        facade.persistShotsV2(100L, list, scriptOfTwoScenes());

        DramaShot row = insertedShots(1).get(0);
        assertEquals(5, row.getDurationSec(),
                "时长缺失要退回项目默认时长（duration_sec NOT NULL）");
        assertEquals("分镜 1", row.getTitle(), "标题缺失要有兜底，别在前端显示空白");
    }

    /** 单场分镜数上限（与 v1 一致）：超出的镜头丢掉，不让一场挂 30 镜。 */
    @Test
    void persistShotsV2_capsShotsPerChapter() {
        ShotDraftList list = new ShotDraftList();
        List<ShotDraftList.ShotDraft> shots = new java.util.ArrayList<>();
        for (int i = 1; i <= DramaContract.MAX_SHOTS_PER_CHAPTER + 3; i++) {
            shots.add(shot(1, i, "镜头" + i));
        }
        list.setShots(shots);
        when(episodeMapper.selectById(100L)).thenReturn(episode());
        stubChapterIds();

        facade.persistShotsV2(100L, list, scriptOfTwoScenes());

        List<DramaShot> rows = insertedShots(DramaContract.MAX_SHOTS_PER_CHAPTER);
        assertEquals(DramaContract.MAX_SHOTS_PER_CHAPTER, rows.size());
        assertEquals(DramaContract.MAX_SHOTS_PER_CHAPTER, rows.get(rows.size() - 1).getShotIndex());
    }

    private ShotDraftList.ShotDraft shot(int chapterIndex, int index, String title) {
        ShotDraftList.ShotDraft s = new ShotDraftList.ShotDraft();
        s.setChapterIndex(chapterIndex);
        s.setIndex(index);
        s.setTitle(title);
        s.setDurationSec(8);
        return s;
    }

    private ScriptDoc scriptOfTwoScenes() {
        ScriptDoc doc = new ScriptDoc();
        ScriptDoc.Scene s1 = new ScriptDoc.Scene();
        s1.setIndex(1);
        s1.setHeading("第一场 教室");
        ScriptDoc.Scene s2 = new ScriptDoc.Scene();
        s2.setIndex(2);
        s2.setHeading("第二场 走廊");
        doc.setScenes(List.of(s1, s2));
        return doc;
    }

    /** 章节 id 按场次发号，便于断言分镜挂到了哪一场。 */
    private void stubChapterIds() {
        when(chapterMapper.insert(any(DramaChapter.class))).thenAnswer(inv -> {
            DramaChapter c = inv.getArgument(0);
            c.setId(c.getChapterIndex() == null ? 99L : c.getChapterIndex() * 10L + 1L);
            return 1;
        });
    }

    private List<DramaShot> insertedShots(int expected) {
        org.mockito.ArgumentCaptor<DramaShot> cap = org.mockito.ArgumentCaptor.forClass(DramaShot.class);
        verify(shotMapper, org.mockito.Mockito.times(expected)).insert(cap.capture());
        return cap.getAllValues();
    }

    @Test
    void buildWarnings_success() {
        DramaAnalysisResult ok = DramaAnalysisResult.builder()
                .skeleton(new cn.aigc.dit.server.drama.text.SkeletonDoc("", "",
                        List.of(), List.of(), List.of()))
                .script(new ScriptDoc())
                .assets(new AssetDraftList())
                .shots(new ShotDraftList())
                .build();
        List<String> w = facade.buildWarnings(ok);
        assertEquals(1, w.size());
        assertTrue(w.get(0).contains("v2 agent"));
    }

    @Test
    void buildWarnings_failure() {
        DramaAnalysisResult fail = DramaAnalysisResult.builder()
                .failedAt(DramaAnalysisResult.Step.SHOTS)
                .stepError(DramaAnalysisResult.Step.SHOTS, "shot fail")
                .build();
        List<String> w = facade.buildWarnings(fail);
        assertTrue(w.get(0).contains("SHOTS"));
    }

    @Test
    void toMetrics_returnsExpectedKeys() {
        DramaAnalysisResult r = DramaAnalysisResult.builder()
                .skeleton(new cn.aigc.dit.server.drama.text.SkeletonDoc("", "",
                        List.of(), List.of(), List.of()))
                .script(new ScriptDoc())
                .assets(new AssetDraftList())
                .shots(new ShotDraftList())
                .addTokens(100, 200)
                .durationMs(500)
                .build();
        var m = facade.toMetrics(r);
        assertEquals(true, m.get("success"));
        assertEquals(100L, m.get("promptTokens"));
        assertEquals(200L, m.get("completionTokens"));
        assertEquals(500L, m.get("durationMs"));
    }

    @Test
    void inferTargetEpisodes_withShortText_returns1() throws Exception {
        var m = DramaAnalysisFacade.class.getDeclaredMethod("inferTargetEpisodes", String.class);
        m.setAccessible(true);
        Integer v = (Integer) m.invoke(facade, "短文本");
        assertEquals(1, v);
    }

    @Test
    void inferTargetEpisodes_withLongText_capsAt20() throws Exception {
        var m = DramaAnalysisFacade.class.getDeclaredMethod("inferTargetEpisodes", String.class);
        m.setAccessible(true);
        String longText = "x".repeat(50_000);
        Integer v = (Integer) m.invoke(facade, longText);
        assertEquals(20, v);   // 50_000 / 1500 = 33, capped to 20
    }
}
