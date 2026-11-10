package cn.aigc.dit.server.drama.agent;

import cn.aigc.dit.server.drama.text.AssetDraftList;

/**
 * 资产抽取 Agent。从 ScriptDoc 抽取角色/场景/道具并写入 AssetDraftList。
 *
 * 流程：
 * 1. system = common + schema_assets + memoryBlock
 * 2. user = ScriptDoc JSON + propsSummary 汇总
 * 3. 调 LLM：整集一次 → 被 max_tokens 截断就加倍预算重放 → 还不行就按场次拆片逐片抽再合并
 * 4. 解析为 AssetDraftList
 *
 * 与原 DramaAssetService.extractFromScript 区别：本类只生成 AssetDraftList（中间态），
 * 不直接写 drama_asset；由 DramaAnalysisOrchestrator 落库。
 */
public interface AssetExtractAgent extends ScriptAgent<AssetExtractReq, AssetDraftList> {

    @Override
    default String agentType() {
        return "assets";
    }
}
