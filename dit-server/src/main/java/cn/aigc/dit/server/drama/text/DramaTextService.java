package cn.aigc.dit.server.drama.text;

/**
 * 乙实现的剧本理解层。主力用 {@code ObjectProvider} 可选注入；无 {@code @Service} Bean 时导入只存
 * {@code source_text}，不堵页面。
 * <p>
 * LLM 只走 {@link TextChatGateway}；禁止读 yml / 环境变量当主路径，禁止自写 HTTP 客户端。
 * 实现类请命名为 {@code DramaTextServiceImpl}（或其它名字），不要删本接口。
 * <p>
 * 阶段 4 锁定 {@code parseScript} 与 {@code extractAssets}；阶段 5 增加 {@code draftShots}。
 */
public interface DramaTextService {

    String ENGINE_RULES = "rules";
    String ENGINE_LLM = "llm";

    /**
     * 该系列本次解析会实际使用的引擎：{@code rules} 或 {@code llm}。
     * 必须与 {@link #parseScript(long, String)} 同源判定，否则 engine 与 script 会不一致。
     */
    String engine(long seriesId);

    /**
     * 原文或梗概 → 分场剧本。无可用文本模型时走规则分场。
     */
    ScriptDoc parseScript(long seriesId, String raw);

    /**
     * 锁定剧本 → 资产草稿。乙未覆盖时走规则去重（人物/道具/场景）；入梦/换装拆行。
     */
    default AssetDraftList extractAssets(long seriesId, ScriptDoc script) {
        return cn.aigc.dit.server.drama.DramaAssetDrafts.fromScript(script);
    }

    /**
     * 锁定剧本 + 资产名 → 章节与分镜草稿。不含 compiled_prompt。
     * 乙未覆盖时走规则拆镜；时长仅 5–15 秒，第一场 4–8 镜。
     */
    default ShotDraftList draftShots(long seriesId, ScriptDoc script, java.util.List<String> assetNames) {
        return draftShots(seriesId, script, assetNames, cn.aigc.dit.server.drama.DramaContract.DEFAULT_DURATION_SEC);
    }

    default ShotDraftList draftShots(long seriesId, ScriptDoc script, java.util.List<String> assetNames, int durationSec) {
        return cn.aigc.dit.server.drama.DramaShotDrafts.fromScript(script, assetNames, durationSec);
    }

    /**
     * 阶段 6 跨集：新一集梗概 → 分场剧本，且必须沿用已锁定资产名。
     * {@code lockedAssetNames} 来自上一集 {@code drama_asset} 已锁定条目；
     * 主力接线 {@code drama_episode.inherited_from_episode_id} 时调本方法即可，两条引擎路径共用同一道守卫。
     */
    default ScriptDoc parseNextEpisode(long seriesId, String raw, java.util.List<String> lockedAssetNames) {
        return EpisodeNameGuard.applyTo(parseScript(seriesId, raw), lockedAssetNames);
    }
}
