package cn.aigc.dit.server.drama.agent;

import cn.aigc.dit.server.drama.text.SkeletonDoc;

/**
 * 故事骨架 Supervisor：本地确定性校验，不调 LLM。
 *
 * 校验规则：
 * 1. episodes.size() == target_episodes（严格；缺失 → error）
 * 2. 每集 scenes_hint.size() <= 6（超出 → warning）
 * 3. characters_recurrent.size() ∈ [1, 8]（越界 → error）
 * 4. world_rules.size() <= 5（超出 → warning）
 *
 * 通过 → pass；任一 error → fail；仅 warning → pass 但带建议。
 */
public interface SkeletonSupervisor extends SupervisorAgent<SkeletonDoc> {

    @Override
    default String supervisorType() {
        return "skeleton_sup";
    }
}
