package cn.aigc.dit.server.drama.text;

/**
 * 剧本理解层。主力用 {@code ObjectProvider} 可选注入；无 {@code @Service} Bean 时由调用方
 * 显式报告服务未就绪。
 * <p>
 * LLM 只走 {@link TextChatGateway}；禁止读 yml / 环境变量当主路径，禁止自写 HTTP 客户端。
 * 实现类请命名为 {@code DramaTextServiceImpl}（或其它名字），不要删本接口。
 * <p>
 * 阶段 4 锁定 {@code parseScript} 与 {@code extractAssets}；阶段 5 增加 {@code draftShots}。
 */
public interface DramaTextService {

    String ENGINE_PENDING = "pending";
    String ENGINE_RULES = "rules";
    String ENGINE_LLM = "llm";
    String UNBOUND_MESSAGE = "项目未绑定文本模型，请先在基础信息中选择文本模型";

    /**
     * 该系列的引擎标识。{@code rules} 仅供旧数据和旧扩展兼容；
     * 正式自动解析链路要求可用的 {@code llm}，不会回退到规则结果。
     */
    String engine(long seriesId);

    /**
     * 原文或梗概 → 分场剧本。无可用文本模型时明确报错。
     */
    ScriptDoc parseScript(long seriesId, String raw);

    /**
     * 锁定剧本 → 资产草稿。此 default 仅保留给旧扩展兼容；
     * 正式实现必须覆盖并走文本模型合同。
     */
    default AssetDraftList extractAssets(long seriesId, ScriptDoc script) {
        return enrichAssets(seriesId, script,
                cn.aigc.dit.server.drama.DramaAssetDrafts.fromScript(script));
    }

    /**
     * 对确定性资产骨架做可选的描述增强。实现必须保留骨架身份，不得让模型新增、删除、改名或改类型。
     */
    default AssetDraftList enrichAssets(long seriesId, ScriptDoc script, AssetDraftList skeleton) {
        return skeleton;
    }

    /**
     * 锁定剧本 + 资产名 → 章节与分镜草稿。不含 compiled_prompt。
     * 此 default 仅保留给旧扩展兼容；正式实现按适中粒度拆镜，仅合并极短且连续的同场草稿。
     */
    default ShotDraftList draftShots(long seriesId, ScriptDoc script, java.util.List<String> assetNames) {
        return draftShots(seriesId, script, assetNames, cn.aigc.dit.server.drama.DramaContract.DEFAULT_DURATION_SEC);
    }

    default ShotDraftList draftShots(long seriesId, ScriptDoc script, java.util.List<String> assetNames, int durationSec) {
        return draftShots(seriesId, script, assetNames, durationSec, null);
    }

    /**
     * {@code durationSec} 只作缺省/钳位参考，不再整表覆盖。
     * {@code episodeBudgetSec} 是整集体量参考（可大于 15），不要把各镜秒数之和强行凑成这个数。
     */
    default ShotDraftList draftShots(
            long seriesId,
            ScriptDoc script,
            java.util.List<String> assetNames,
            int durationSec,
            Integer episodeBudgetSec) {
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
