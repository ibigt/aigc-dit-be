package cn.aigc.dit.server.drama;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
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
import cn.aigc.dit.server.drama.text.ScriptDocs;
import cn.aigc.dit.server.drama.text.ShotDraftList;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DramaAnalysisOrchestrator 与业务侧（Controller / DramaService）的桥梁。
 *
 * 职责：
 * 1. 把 4 个 Agent 输出的中间态（SkeletonDoc / ScriptDoc / AssetDraftList / ShotDraftList）
 *    落回 legacy schema：drama_episode.script + drama_asset + drama_shot
 * 2. 维护 drama_episode 的 scriptAnalyzingAt / scriptAnalyzeError 状态
 * 3. 出错时抛 BizException，让调用方决定是否降级到旧 analyzeScript
 *
 * 调用方（DramaService）流程：
 *   try {
 *       facade.analyzeV2(episode, raw);   // 新流水线
 *   } catch (BizException e) {
 *       fallbackLegacyAnalyzeScript(...); // 旧逻辑兜底
 *   }
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DramaAnalysisFacade {

    /** engine 字段：标记这一集是用 v2 agent 流水生成的。 */
    public static final String ENGINE_V2 = "v2-agent";

    /** drama_shot.title 的列宽。 */
    private static final int TITLE_MAX_LEN = 128;

    /** drama_shot.continuity_in / continuity_out 的列宽。 */
    private static final int CONTINUITY_MAX_LEN = 256;

    /** drama_shot.location_name 的列宽。 */
    private static final int LOCATION_MAX_LEN = 128;

    private final DramaAnalysisOrchestrator orchestrator;
    private final DramaEpisodeMapper episodeMapper;
    private final DramaAssetMapper assetMapper;
    private final DramaShotMapper shotMapper;
    private final DramaChapterMapper chapterMapper;
    private final DramaSeriesMapper seriesMapper;

    @Autowired(required = false)
    private ObjectProvider<DramaAnalysisFacade> self;

    /** 与 v1 共用的分析锁：置/清 script_analyzing_at，前端据此显示进度并防并发。 */
    @Autowired(required = false)
    private DramaScriptAnalyzeLock analyzeLock;

    /**
     * 主入口：跑 4 步 Agent 流水线并把结果落库。
     *
     * 失败语义：
     * - 抛出 BizException(code=LLM_FAIL)，error 包含 failedAt 步骤
     * - 调用方应捕获并降级到旧 analyzeScript
     *
     * @param episode 已存在（requireEpisode 通过）
     * @param rawText 用户粘贴的原始文本
     * @param promptVersion  关联的 prompt 版本（用于 A/B 对比，可空）
     * @param model  调用的模型标识（可空）
     */
    public DramaAnalysisResult analyzeV2(DramaEpisode episode, String rawText,
                                         String promptVersion, String model) {
        long seriesId = episode.getSeriesId();
        long episodeId = episode.getId();

        // 占用分析锁（与 v1 同一把）：置 script_analyzing_at，前端据此显示「分析中」，
        // 并阻止同一集并发分析。结束时必须 release / fail，否则标记会残留 15 分钟，
        // 前端会一直显示分析中、按钮一直禁用。
        if (analyzeLock != null) {
            analyzeLock.claim(episodeId);
        }
        boolean failed = false;
        // 心跳：慢模型跑一集十几分钟，锁不能因为固定 15 分钟窗口被判过期
        //（过期后页面进度自己清空，用户还能再点一次分析，两次分析写同一集）。
        DramaScriptAnalyzeLock.Heartbeat heartbeat = analyzeLock == null
                ? null
                : analyzeLock.startHeartbeat(episodeId);
        try {
            return analyzeV2Locked(episode, rawText, promptVersion, model, episodeId, seriesId);
        } catch (RuntimeException e) {
            failed = true;
            // 落库阶段失败也要写错误、清标记，否则前端既看不到错误也看不到进度
            if (analyzeLock != null) {
                analyzeLock.fail(episodeId, e.getMessage());
            }
            throw e;
        } finally {
            if (heartbeat != null) {
                heartbeat.stop();
            }
            if (!failed && analyzeLock != null) {
                analyzeLock.release(episodeId);
            }
        }
    }

    private DramaAnalysisResult analyzeV2Locked(DramaEpisode episode, String rawText,
                                                String promptVersion, String model,
                                                long episodeId, long seriesId) {
        String sessionId = UUID.randomUUID().toString();
        // 原文先落库，再跑流水线：v2 一跑就是几分钟，页面刷新或切到「资产/分镜」页之后
        // 原文要能从服务端取回来。v1 在 importSource 里顺手存了原文，v2 之前漏了这一步，
        // 于是用户输入的剧本只活在浏览器内存里——刷新一次就没了。
        String sourceText = rawText == null ? "" : rawText.trim();
        if (!sourceText.isEmpty()) {
            selfOps().persistSourceText(episodeId, sourceText);
        }

        DramaAnalysisRequest req = DramaAnalysisRequest.fullRun(
                seriesId, episodeId, sessionId, rawText,
                /* targetEpisodes= */ inferTargetEpisodes(rawText),
                promptVersion == null ? "" : promptVersion,
                model == null ? "" : model);

        DramaAnalysisResult result = orchestrator.analyze(req);
        if (!result.success()) {
            String reason = result.stepErrors().get(result.failedAt());
            // 错误标记与标记清理统一交给外层的 catch 处理，避免重复 fail
            throw new BizException(ErrorCode.INTERNAL,
                    "剧本分析在「" + DramaErrorText.step(result.failedAt()) + "」这一步失败："
                            + (reason == null || reason.isBlank() ? "原因未知" : reason));
        }

        // 4 步全部成功：翻译中间态到 legacy schema（短事务提交，避免长事务）
        DramaAnalysisFacade ops = selfOps();
        try {
            ops.persistScriptV2(episodeId, result.script());
            ops.persistAssetsV2(episodeId, result.assets(), result.script());
            ops.persistShotsV2(episodeId, result.shots(), result.script());
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw persistFailed(episodeId, e);
        }

        log.info("[facade] 4 步全成功 episodeId={} token=({}+{}) duration={}ms",
                episodeId, result.promptTokens(), result.completionTokens(), result.durationMs());
        return result;
    }

    /**
     * 重跑下游（assets + shots），剧本已存在时使用。
     */
    public DramaAnalysisResult regenerateDownstream(DramaEpisode episode, ScriptDoc existingScript,
                                                     String promptVersion, String model) {
        long seriesId = episode.getSeriesId();
        long episodeId = episode.getId();
        String sessionId = UUID.randomUUID().toString();
        String raw = episode.getSourceText() == null ? "" : episode.getSourceText();

        DramaAnalysisRequest req = DramaAnalysisRequest.downstreamOnly(
                seriesId, episodeId, sessionId, raw,
                /* skeleton= */ null, existingScript,
                promptVersion == null ? "" : promptVersion,
                model == null ? "" : model);

        if (analyzeLock != null) {
            analyzeLock.claim(episodeId);
        }
        boolean failed = false;
        DramaScriptAnalyzeLock.Heartbeat heartbeat = analyzeLock == null
                ? null
                : analyzeLock.startHeartbeat(episodeId);
        try {
            DramaAnalysisResult result = orchestrator.analyze(req);
            if (!result.success()) {
                String reason = result.stepErrors().get(result.failedAt());
                throw new BizException(ErrorCode.INTERNAL,
                        "重新生成在「" + DramaErrorText.step(result.failedAt()) + "」这一步失败："
                                + (reason == null || reason.isBlank() ? "原因未知" : reason));
            }
            DramaAnalysisFacade ops = selfOps();
            try {
                ops.persistAssetsV2(episodeId, result.assets(), result.script());
                ops.persistShotsV2(episodeId, result.shots(), result.script());
            } catch (BizException e) {
                throw e;
            } catch (RuntimeException e) {
                throw persistFailed(episodeId, e);
            }
            return result;
        } catch (RuntimeException e) {
            failed = true;
            if (analyzeLock != null) {
                analyzeLock.fail(episodeId, e.getMessage());
            }
            throw e;
        } finally {
            if (heartbeat != null) {
                heartbeat.stop();
            }
            if (!failed && analyzeLock != null) {
                analyzeLock.release(episodeId);
            }
        }
    }

    // ─────────────────────────────────────
    // 翻译中间态到 legacy schema
    // ─────────────────────────────────────

    /**
     * 只补齐 drama_episode.source_text（剧本原文），不碰 script / status。
     *
     * <p>与 v1 的 importSource 不同：v1 顺带把状态置为「待确认」并可能覆盖已锁定的 script，
     * 这里只存原文——v2 的 script 与状态由 {@link #persistScriptV2} 统一写。
     *
     * <p>下游重跑（regenerateDownstream）直接读 episode.source_text 作为模型输入，
     * 原文没落库时重跑等于让模型凭空编一版。
     *
     * <p>这里是稀疏更新：analysis 锁、错误提示这些列都不带 ALWAYS（见 {@link DramaEpisode}），
     * 所以只写 source_text 一列，不会把刚占上的分析锁清掉。
     */
    @Transactional
    public void persistSourceText(long episodeId, String sourceText) {
        DramaEpisode update = new DramaEpisode();
        update.setId(episodeId);
        update.setSourceText(sourceText);
        episodeMapper.updateById(update);
    }

    /**
     * 落库 ScriptDoc 到 drama_episode.script（JSONB）。
     *
     * <p>状态写「已锁定」，不是 v2 工作流值 {@code scripted}。
     *
     * <p>v1 的 analyzeScript 是「导入原文 → 锁定剧本 → 从剧本抽资产 → 后台拆镜」，
     * 分析完的剧本状态就是「已锁定」，资产和分镜都从这份锁定的剧本派生。v2 一步做完
     * 同样的事，却把状态留在 {@code scripted} —— 而所有下游门禁都按「已锁定」判断：
     * 资产页的「从剧本提取」按钮、后端的 extractFromScript、分镜页的「智能分镜」
     * （canDraft）。于是 v2 分析完的剧集在资产页仍然提示
     * 「请先回到剧本页，完成一键分析后再提取」，其实早就分析完了。
     *
     * <p>错误提示列不在这里写：它由分析锁的专用 SQL 管（claim 时清空、fail 时写入），
     * 稀疏更新不碰它，免得把别处的错误信息抹掉。
     */
    @Transactional
    public void persistScriptV2(long episodeId, ScriptDoc doc) {
        if (doc == null) return;
        Map<String, Object> scriptMap = ScriptDocs.toMap(doc);
        DramaEpisode update = new DramaEpisode();
        update.setId(episodeId);
        update.setScript(scriptMap);
        update.setEngine(ENGINE_V2);
        update.setStatus(DramaContract.STATUS_LOCKED);
        episodeMapper.updateById(update);
    }

    /**
     * 落库资产到 drama_asset。
     *
     * 策略：先删后插（保证完全反映 v2 输出的资产清单）。
     * 角色的 name 是族名（洛白），本体与入梦版本同名、靠 state_tag 区分 ——
     * 与 drama_asset 的唯一约束 (episode_id, type, name, state_tag) 对得上。
     *
     * 落库前会走一遍 {@link DramaAssetDrafts#ensurePrompts}（v1 同款兜底）：
     * 骨架句清理、description→identity 提升、按项目风格拼出完整生图提示词。
     * 没有这一步，v2 落库的 image_prompt 会一直是空串（模型被要求留空），
     * 页面「外形设定」也就只剩模型没写全的空 identity。
     */
    @Transactional
    public void persistAssetsV2(long episodeId, AssetDraftList assets, ScriptDoc script) {
        if (assets == null || assets.getAssets() == null) return;
        // 删旧
        assetMapper.delete(new LambdaQueryWrapper<DramaAsset>().eq(DramaAsset::getEpisodeId, episodeId));
        // 插新
        DramaEpisode ep = episodeMapper.selectById(episodeId);
        if (ep == null) return;
        ensureAssetPrompts(ep.getSeriesId(), assets, script);
        List<DramaAsset> rows = new ArrayList<>();
        for (AssetDraftList.AssetDraft a : assets.getAssets()) {
            DramaAsset row = new DramaAsset();
            row.setSeriesId(ep.getSeriesId());
            row.setEpisodeId(episodeId);
            row.setUserId(ep.getUserId());
            row.setName(a.getName());
            row.setType(a.getType());
            row.setStateTag(a.getStateTag());
            row.setDescription(a.getDescription());
            row.setIdentityInvariants(a.getIdentityInvariants());
            row.setImagePrompt(a.getImagePrompt());
            row.setVoiceLock(a.getVoiceLock());
            row.setMustSplit(Boolean.TRUE.equals(a.getMustSplit()));
            row.setNeedsHuman(false);
            row.setStatus(DramaContract.STATUS_PENDING_CONFIRM);
            row.setIdentityVersion(1);
            rows.add(row);
        }
        if (!rows.isEmpty()) {
            // 简单 for 循环 insert：行数 ≤ 30，可接受
            for (DramaAsset r : rows) {
                assetMapper.insert(r);
            }
        }
    }

    /**
     * 落库分镜到 drama_shot。
     *
     * <p>策略：先删后插（与 persistAssetsV2 保持一致）。
     * chapterIndex 在 v2 输出里就是 sceneIndex，用来挂章节；
     * <b>shot_index 不能用模型给的 index</b>——那是「场内序号」，每场都从 1 开始，
     * 而 shot_index 在本集内必须唯一（uq_drama_shot_episode_index），照抄必然撞库。
     */
    @Transactional
    public void persistShotsV2(long episodeId, ShotDraftList shots, ScriptDoc script) {
        if (shots == null || shots.getShots() == null) return;
        if (shotMapper == null) {
            log.warn("[facade] DramaShotMapper 未注入，跳过分镜落库 episodeId={}", episodeId);
            return;
        }
        DramaEpisode ep = episodeMapper.selectById(episodeId);
        if (ep == null) return;

        // 删旧（章节与分镜一起删，避免留下没有分镜的孤儿章节）
        shotMapper.delete(new LambdaQueryWrapper<DramaShot>().eq(DramaShot::getEpisodeId, episodeId));
        if (chapterMapper != null) {
            chapterMapper.delete(new LambdaQueryWrapper<DramaChapter>().eq(DramaChapter::getEpisodeId, episodeId));
        }

        // 按场次补建章节。v2 流水线没有独立的「章节」步骤（骨架→剧本→语义归一化→资产→分镜），
        // 但 drama_shot.chapter_id 是 NOT NULL —— 不建章节就插不进分镜。
        // 章节语义与 v1 一致：一个场次一个章节，chapter_index = scene_index。
        Map<Integer, DramaChapter> chapters = new LinkedHashMap<>();
        if (chapterMapper != null && script != null && script.getScenes() != null) {
            int fallback = 0;
            for (ScriptDoc.Scene scene : script.getScenes()) {
                if (scene == null) continue;
                fallback++;
                int index = scene.getIndex() == null ? fallback : scene.getIndex();
                DramaChapter chapter = new DramaChapter();
                chapter.setSeriesId(ep.getSeriesId());
                chapter.setEpisodeId(episodeId);
                chapter.setUserId(ep.getUserId());
                chapter.setChapterIndex(index);
                chapter.setSceneIndex(index);
                chapter.setName(DramaShotDrafts.chapterName(scene, index));
                chapter.setBody(DramaShotDrafts.chapterBody(scene));
                chapter.setStatus(DramaContract.STATUS_PENDING_CONFIRM);
                chapterMapper.insert(chapter);
                chapters.put(index, chapter);
            }
        }

        List<DramaShot> rows = new ArrayList<>();
        // shot_index 在本集内必须唯一（uq_drama_shot_episode_index），而模型给的 index 是
        // 「场内序号」——每一场都从 1 开始。照抄必然撞唯一约束：第二场的第 1 镜与第一场的
        // 第 1 镜同号，整集落库直接失败（v2 分析就是这么炸的）。
        // 与 v1 老链路一致：先按场次排好序，再在本集内统一发号 1,2,3…。
        int shotIndex = 0;
        int fallbackDuration = defaultDurationSec(ep);
        Map<Integer, Integer> perChapter = new HashMap<>();
        for (ShotDraftList.ShotDraft s : orderShots(shots.getShots(), chapters)) {
            DramaChapter chapter = resolveChapter(chapters, s.getChapterIndex());
            if (chapter == null) {
                log.warn("[facade] 分镜找不到对应章节，跳过该镜 chapterIndex={} episodeId={}",
                        s.getChapterIndex(), episodeId);
                continue;
            }
            int chapterKey = chapter.getChapterIndex() == null ? 0 : chapter.getChapterIndex();
            int used = perChapter.getOrDefault(chapterKey, 0);
            if (used >= DramaContract.MAX_SHOTS_PER_CHAPTER) {
                log.warn("[facade] 单场分镜数超过 {} 镜，跳过该镜 chapterIndex={} episodeId={}",
                        DramaContract.MAX_SHOTS_PER_CHAPTER, chapterKey, episodeId);
                continue;
            }
            perChapter.put(chapterKey, used + 1);
            shotIndex++;
            // 镜头序列（blocking + beats）与 camera/ambiance 必须落库：
            // 编译器按 hasBeats() 分支渲染可见正文 —— beats 丢了就只剩「场景/运镜/动作」三行，
            // 站位、镜头N 和环境声全没有，用户看到的视频提示词比 v1 干得多。
            DramaShotBeats beats = DramaShotBeats.normalize(s.getShotBeats());
            s.setShotBeats(beats);
            DramaShotBeats.syncDraftDialogue(s);

            DramaShot row = new DramaShot();
            row.setSeriesId(ep.getSeriesId());
            row.setEpisodeId(episodeId);
            row.setChapterId(chapter.getId());
            row.setUserId(ep.getUserId());
            row.setShotIndex(shotIndex);
            row.setTitle(shotTitle(s.getTitle(), shotIndex));
            row.setDurationSec(shotDuration(s.getDurationSec(), fallbackDuration));
            row.setNarrativeGoal(DramaStoryboardService.blankToNull(s.getNarrativeGoal()));
            row.setCameraMotion(DramaStoryboardService.blankToNull(s.getCamera()));
            row.setAmbianceAudio(DramaStoryboardService.blankToNull(s.getAmbiance()));
            row.setShotBeats(beats);
            row.setScene(DramaStoryboardService.blankToNull(s.getScene()));
            row.setDialogue(DramaStoryboardService.blankToNull(s.getDialogue()));
            row.setContinuityIn(cap(DramaStoryboardService.blankToNull(s.getContinuityIn()), CONTINUITY_MAX_LEN));
            row.setContinuityOut(cap(DramaStoryboardService.blankToNull(s.getContinuityOut()), CONTINUITY_MAX_LEN));
            List<String> characters = DramaStoryboardService.cleanNames(s.getCharacterNames());
            row.setCharacterNames(characters);
            row.setPropNames(DramaStoryboardService.cleanNames(s.getPropNames()));
            row.setLocationName(cap(resolveLocationName(s, script, s.getChapterIndex()), LOCATION_MAX_LEN));
            // cast_count 必须等于实际出场人数，否则出片时的 Cast 约束会数错人。
            row.setCastCount(characters.size());
            row.setStatus(DramaContract.STATUS_PENDING_CONFIRM);
            row.setReferenceAssetIds(new ArrayList<>());
            DramaVideoGenerateModes.applyProductDefault(row);
            rows.add(row);
        }
        if (!rows.isEmpty()) {
            for (DramaShot r : rows) {
                shotMapper.insert(r);
            }
        }
    }

    /**
     * 分镜落库顺序：先按场次（章节）再按模型给的场内序号排序。
     *
     * <p>模型偶尔不按场次顺序输出分镜；不排的话本集发出来的 shot_index 就与场次顺序对不上，
     * 前端按 shot_index 排就会看到镜头跨场乱序。与 v1 老链路（{@code normalizeShots}）一致。
     * 排序是稳定的：同一场内 index 相同（或缺失）时保持模型给出的先后。
     */
    private List<ShotDraftList.ShotDraft> orderShots(List<ShotDraftList.ShotDraft> drafts,
                                                      Map<Integer, DramaChapter> chapters) {
        List<ShotDraftList.ShotDraft> list = new ArrayList<>();
        for (ShotDraftList.ShotDraft draft : drafts) {
            if (draft != null) {
                list.add(draft);
            }
        }
        list.sort(Comparator
                .comparingInt((ShotDraftList.ShotDraft draft) -> chapterOrder(chapters, draft.getChapterIndex()))
                .thenComparingInt(draft -> draft.getIndex() == null ? Integer.MAX_VALUE : draft.getIndex()));
        return list;
    }

    /** 该分镜归属场次的排序键（章节找不到时归到第一个章节）。 */
    private int chapterOrder(Map<Integer, DramaChapter> chapters, Integer chapterIndex) {
        DramaChapter chapter = resolveChapter(chapters, chapterIndex);
        if (chapter == null || chapter.getChapterIndex() == null) {
            return Integer.MAX_VALUE;
        }
        return chapter.getChapterIndex();
    }

    /** 分镜标题：模型没给就用「分镜 N」兜底（与 v1 一致，前端别显示空白）。 */
    private String shotTitle(String title, int shotIndex) {
        String text = title == null ? "" : title.trim();
        if (text.isEmpty()) {
            text = "分镜 " + shotIndex;
        }
        return cap(text, TITLE_MAX_LEN);
    }

    /** 镜头时长：缺失或超出 5~15 一律退回项目默认时长（duration_sec NOT NULL）。 */
    private Integer shotDuration(Integer durationSec, int fallbackSec) {
        if (durationSec != null && DramaContract.DURATIONS.contains(durationSec)) {
            return durationSec;
        }
        return fallbackSec;
    }

    /** 项目默认单镜时长（series.default_duration_sec），拿不到就用合同默认值。 */
    private int defaultDurationSec(DramaEpisode episode) {
        if (episode == null || seriesMapper == null) {
            return DramaContract.DEFAULT_DURATION_SEC;
        }
        try {
            DramaSeries series = seriesMapper.selectById(episode.getSeriesId());
            Integer value = series == null ? null : series.getDefaultDurationSec();
            return value != null && DramaContract.DURATIONS.contains(value)
                    ? value : DramaContract.DEFAULT_DURATION_SEC;
        } catch (RuntimeException e) {
            log.warn("[facade] 读取项目默认时长失败 seriesId={} err={}", episode.getSeriesId(), e.getMessage());
            return DramaContract.DEFAULT_DURATION_SEC;
        }
    }

    /** 截断到列宽上限（VARCHAR 超长会整条 INSERT 失败）。 */
    private String cap(String value, int maxLen) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLen ? value : value.substring(0, maxLen);
    }

    /**
     * 分镜地点：模型给的 location_name 优先，缺省时用所属场次的地点。
     */
    private String resolveLocationName(ShotDraftList.ShotDraft draft, ScriptDoc script, Integer chapterIndex) {
        if (draft != null && draft.getLocationName() != null && !draft.getLocationName().isBlank()) {
            return draft.getLocationName().trim();
        }
        if (script == null || script.getScenes() == null || chapterIndex == null) {
            return null;
        }
        for (ScriptDoc.Scene scene : script.getScenes()) {
            if (scene == null) continue;
            if (chapterIndex.equals(scene.getIndex())) {
                return scene.getLocation();
            }
        }
        return null;
    }

    /**
     * 落库前补齐资产提示词（与 v1 的 {@link DramaAssetDrafts#ensurePrompts} 同款兜底）。
     *
     * 做三件事：
     * 1. 清掉模型残留的骨架占位句，能提升的用 description 顶上；
     * 2. 按项目视觉风格拼出完整生图提示词（v2 的 prompt 要求模型把 image_prompt 留空，
     *    不拼的话前端「完整提示词」永远是空的）；
     * 3. 拿不到项目风格时不阻断落库，只退回默认风格继续拼。
     */
    private void ensureAssetPrompts(long seriesId, AssetDraftList assets, ScriptDoc script) {
        DramaStyles.Spec style = null;
        if (seriesMapper != null) {
            try {
                DramaSeries series = seriesMapper.selectById(seriesId);
                if (series != null) {
                    style = DramaStyles.resolveOrDefault(series.getStyleKey());
                }
            } catch (RuntimeException e) {
                log.warn("[facade] 读取项目风格失败 seriesId={} err={}", seriesId, e.getMessage());
            }
        }
        if (style == null) {
            style = DramaStyles.resolveOrDefault(null);
        }
        DramaAssetDrafts.ensurePrompts(assets, script, style);
    }

    /** 按 chapterIndex 找章节；找不到时退到最近的章节（与 v1 落库逻辑一致）。 */
    private DramaChapter resolveChapter(Map<Integer, DramaChapter> chapters, Integer chapterIndex) {
        if (chapters.isEmpty()) return null;
        if (chapterIndex != null) {
            DramaChapter exact = chapters.get(chapterIndex);
            if (exact != null) return exact;
            Integer nearest = null;
            for (Integer key : chapters.keySet()) {
                if (nearest == null || Math.abs(key - chapterIndex) < Math.abs(nearest - chapterIndex)) {
                    nearest = key;
                }
            }
            if (nearest != null) return chapters.get(nearest);
        }
        // 没有 chapterIndex 的镜头归到第一个章节，保证不丢分镜
        return chapters.values().iterator().next();
    }

    // ─────────────────────────────────────
    // 状态维护
    // ─────────────────────────────────────




    // ─────────────────────────────────────
    // 工具
    // ─────────────────────────────────────

    /**
     * 从原文长度粗略推目标集数（每 1500 字 ≈ 1 集）。
     * 后续可由用户在 ProjectMemoryService 或 Req 中显式传入。
     */
    private Integer inferTargetEpisodes(String raw) {
        if (raw == null || raw.isBlank()) return 1;
        int len = raw.length();
        int guess = Math.max(1, len / 1500);
        return Math.min(20, guess);
    }

    /**
     * 走 Spring 代理，保证 @Transactional 生效。
     */
    private DramaAnalysisFacade selfOps() {
        if (self == null) return this;
        DramaAnalysisFacade bean = self.getIfAvailable();
        return bean != null ? bean : this;
    }

    /** 从 JDBC 约束异常里提取约束名，用于给前端一个不泄漏 SQL 的友好提示。 */
    private static final java.util.regex.Pattern CONSTRAINT_PATTERN = java.util.regex.Pattern.compile(
            "violates (?:check|not-null|foreign key|unique) constraint \"?([^\"\\s)]+)\"?");

    /**
     * 把落库阶段的异常统一转成 BizException：完整原因只在服务端日志留痕，
     * 前端只看到友好文案 + 约束名，不泄漏原始 SQL，且能命中 controller 的 BizException 分支。
     */
    private BizException persistFailed(long episodeId, RuntimeException e) {
        log.warn("[facade] 落库失败 episodeId={} type={} msg={}",
                episodeId, e.getClass().getSimpleName(), e.getMessage());
        String msg = e.getMessage() == null ? "" : e.getMessage();
        java.util.regex.Matcher m = CONSTRAINT_PATTERN.matcher(msg);
        String detail = m.find() ? "，违反数据库约束 " + m.group(1) : "";
        return new BizException(ErrorCode.INTERNAL, "分析结果落库失败" + detail);
    }

    /**
     * 把 DramaAnalysisResult 摘要渲染为警告列表（用于返回给前端的诊断信息）。
     */
    public List<String> buildWarnings(DramaAnalysisResult result) {
        if (result == null) return List.of();
        List<String> warnings = new ArrayList<>();
        if (result.success()) {
            warnings.add("已用 v2 agent 流水线完成分析。");
        } else {
            warnings.add("v2 流水线在 " + result.failedAt() + " 步终止：" + result.stepErrors().get(result.failedAt()));
        }
        return warnings;
    }

    /**
     * 统计摘要（用于监控告警）。
     */
    public Map<String, Object> toMetrics(DramaAnalysisResult result) {
        Map<String, Object> m = new HashMap<>();
        if (result == null) return m;
        m.put("success", result.success());
        m.put("failedAt", result.failedAt() == null ? null : result.failedAt().name());
        m.put("promptTokens", result.promptTokens());
        m.put("completionTokens", result.completionTokens());
        m.put("durationMs", result.durationMs());
        return m;
    }
}
