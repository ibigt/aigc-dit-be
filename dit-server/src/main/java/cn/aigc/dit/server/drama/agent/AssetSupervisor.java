package cn.aigc.dit.server.drama.agent;

import cn.aigc.dit.server.drama.text.AssetDraftList;

/**
 * 资产抽取 Supervisor：本地确定性校验。
 *
 * 校验规则：
 * 1. 每个 scene.props 都有对应 type=道具 的资产条目
 * 2. type=角色 且 must_split=false 时不与 state_tag=dream/costume 同名存在（互斥）
 * 3. identity_invariants 长度 ≤ 80
 * 4. description 长度 ≤ 100
 */
public interface AssetSupervisor extends SupervisorAgent<AssetDraftList> {

    @Override
    default String supervisorType() {
        return "assets_sup";
    }
}
