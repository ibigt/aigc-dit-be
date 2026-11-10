package cn.aigc.dit.server.drama.agent;

import cn.aigc.dit.server.drama.text.ShotDraftList;

/**
 * 分镜草稿 Supervisor：本地确定性校验。
 *
 * 校验规则：
 * 1. shots[].duration_sec ∈ [5, 15]
 * 2. shots[].dialogue 能在 ScriptDoc 对白中找到（substring 匹配）
 * 3. cast_count == character_names.size
 * 4. 同一 chapter_index 的 shot 数不超过合理上界（防爆拆）
 */
public interface ShotSupervisor extends SupervisorAgent<ShotDraftList> {

    @Override
    default String supervisorType() {
        return "shots_sup";
    }
}
