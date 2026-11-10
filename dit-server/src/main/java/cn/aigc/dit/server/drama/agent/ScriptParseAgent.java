package cn.aigc.dit.server.drama.agent;

import cn.aigc.dit.server.drama.text.ScriptDoc;

/**
 * 剧本分场 Agent。把原始文本解析为结构化 ScriptDoc（覆盖传统 DramaTextServiceImpl.parseScript 路径）。
 *
 * 流程：
 * 1. 拼 system = common + schema_script + memoryBlock + skeletonContext（如有）
 * 2. 拼 user = 原文
 * 3. 调 LLM 跑 2 次重试
 * 4. 解析为 ScriptDoc
 *
 * 与原 DramaTextServiceImpl.parseScript 的区别：
 * - 接收 SkeletonDoc 作为引导
 * - 走 ScriptAgent 统一契约
 * - 自动接受 ScriptSupervisor 校验 + 修复重试
 */
public interface ScriptParseAgent extends ScriptAgent<ScriptParseReq, ScriptDoc> {

    @Override
    default String agentType() {
        return "script";
    }
}
