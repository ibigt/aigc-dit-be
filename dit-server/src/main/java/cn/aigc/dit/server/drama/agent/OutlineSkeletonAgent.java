package cn.aigc.dit.server.drama.agent;

import cn.aigc.dit.server.drama.text.SkeletonDoc;

/**
 * 故事骨架 Agent。覆盖 Toonflow 的骨架 subAgent + 改编策略 subAgent 的合并产物。
 *
 * 流程：
 * 1. 拼 system = common + schema_skeleton + memoryBlock
 * 2. 拼 user = 原文 + target_episodes
 * 3. 调 TextChatGateway 跑 attemptJson（含 2 次重试 + 截断缩片）
 * 4. 解析为 SkeletonDoc
 */
public interface OutlineSkeletonAgent extends ScriptAgent<OutlineReq, SkeletonDoc> {

    @Override
    default String agentType() {
        return "skeleton";
    }
}
