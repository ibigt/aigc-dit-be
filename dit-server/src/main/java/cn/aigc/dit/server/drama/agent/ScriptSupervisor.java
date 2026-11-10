package cn.aigc.dit.server.drama.agent;

import cn.aigc.dit.server.drama.text.ScriptDoc;

/**
 * 剧本分场 Supervisor：本地确定性校验。
 *
 * 校验规则（依赖 skeleton 用于跨阶段一致性）：
 * 1. characters_recurrent 中每个角色至少在 1 个 scene 出场（需 skeleton）
 * 2. 每个 scene.props.size() <= 3
 * 3. 每个 scene.characters.size() >= 1
 * 4. dialogue.line 严格保留原文（与原文 raw 做子串匹配）
 * 5. scene 数量与 skeleton.scenes_hint 偏差 ≤ 2（柔性）
 */
public interface ScriptSupervisor extends SupervisorAgent<ScriptDoc> {

    @Override
    default String supervisorType() {
        return "script_sup";
    }
}
