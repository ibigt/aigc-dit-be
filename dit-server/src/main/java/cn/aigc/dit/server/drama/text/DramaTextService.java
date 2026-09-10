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
     * 本次解析实际使用的引擎：{@code rules} 或 {@code llm}。
     */
    String engine();

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
     * 乙未覆盖时走规则拆镜；时长仅 5/10/15，第一场 4–8 镜。
     */
    default ShotDraftList draftShots(long seriesId, ScriptDoc script, java.util.List<String> assetNames) {
        return cn.aigc.dit.server.drama.DramaShotDrafts.fromScript(script, assetNames);
    }
}
