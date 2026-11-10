package cn.aigc.dit.server.drama.agent;

import cn.aigc.dit.server.drama.text.ShotDraftList;

/**
 * 分镜草稿 Agent。从 ScriptDoc + 资产清单生成 ShotDraftList。
 *
 * 流程：
 * 1. system = common + schema_shots + memoryBlock
 * 2. user = ScriptDoc 对白汇总 + 资产名清单
 * 3. 调 LLM：整集一次 → 被 max_tokens 截断就加倍预算重放 → 还不行就按场次拆片逐片出镜再合并
 * 4. 解析为 ShotDraftList
 *
 * 与原 DramaStoryboardService 区别：本类只生成分镜草稿（中间态），不直接写 drama_shot；
 * 由 DramaAnalysisOrchestrator 落库。
 */
public interface ShotDraftAgent extends ScriptAgent<ShotDraftReq, ShotDraftList> {

    @Override
    default String agentType() {
        return "shots";
    }
}
