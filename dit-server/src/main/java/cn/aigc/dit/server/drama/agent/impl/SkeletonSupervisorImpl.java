package cn.aigc.dit.server.drama.agent.impl;

import cn.aigc.dit.server.drama.agent.AgentContext;
import cn.aigc.dit.server.drama.agent.OutlineReq;
import cn.aigc.dit.server.drama.agent.SkeletonSupervisor;
import cn.aigc.dit.server.drama.agent.SupervisorIssue;
import cn.aigc.dit.server.drama.agent.SupervisorVerdict;
import cn.aigc.dit.server.drama.text.SkeletonDoc;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * SkeletonSupervisor 实现：纯本地确定性校验，无 LLM 调用（毫秒级返回）。
 */
@Component
public class SkeletonSupervisorImpl implements SkeletonSupervisor {

    /** 单集最大 scenes_hint 数。 */
    private static final int MAX_SCENES_HINT_PER_EPISODE = 6;
    /** characters_recurrent 数量区间。 */
    private static final int MIN_CHARACTERS_RECURRENT = 1;
    private static final int MAX_CHARACTERS_RECURRENT = 8;
    /** world_rules 上限。 */
    private static final int MAX_WORLD_RULES = 5;

    @Override
    public SupervisorVerdict validate(AgentContext ctx, SkeletonDoc skeleton, Object originalInput) {
        List<SupervisorIssue> issues = new ArrayList<>();

        Integer targetEpisodes = (originalInput instanceof OutlineReq req) ? req.targetEpisodes() : null;
        int actualSize = skeleton.episodes() == null ? 0 : skeleton.episodes().size();

        // 规则 1：episodes 数量对齐
        if (targetEpisodes != null && actualSize != targetEpisodes) {
            issues.add(new SupervisorIssue(
                    "episodes.size()",
                    SupervisorIssue.ERROR,
                    "episodes 数量 " + actualSize + " 与 target_episodes=" + targetEpisodes + " 不一致",
                    "请把 episodes 数量调整到恰好 " + targetEpisodes + " 条"));
        } else if (targetEpisodes == null && actualSize == 0) {
            issues.add(new SupervisorIssue(
                    "episodes.size()",
                    SupervisorIssue.ERROR,
                    "episodes 数组为空，未生成任何分集",
                    "请按原文长度合理推断集数（建议 4~12 集）"));
        }

        // 规则 2：每集 scenes_hint <= 6
        if (skeleton.episodes() != null) {
            for (int i = 0; i < skeleton.episodes().size(); i++) {
                SkeletonDoc.EpisodeOutline ep = skeleton.episodes().get(i);
                int hintSize = ep.scenesHint() == null ? 0 : ep.scenesHint().size();
                if (hintSize > MAX_SCENES_HINT_PER_EPISODE) {
                    issues.add(new SupervisorIssue(
                            "episodes[" + i + "].scenes_hint.size()",
                            SupervisorIssue.WARNING,
                            "第 " + (i + 1) + " 集 scenes_hint 有 " + hintSize + " 条，超过 " + MAX_SCENES_HINT_PER_EPISODE + " 条上限",
                            "合并为不超过 " + MAX_SCENES_HINT_PER_EPISODE + " 条核心场景"));
                }
            }
        }

        // 规则 3：characters_recurrent ∈ [1, 8]
        int charSize = skeleton.charactersRecurrent() == null ? 0 : skeleton.charactersRecurrent().size();
        if (charSize < MIN_CHARACTERS_RECURRENT) {
            issues.add(new SupervisorIssue(
                    "characters_recurrent.size()",
                    SupervisorIssue.ERROR,
                    "characters_recurrent 为空（" + charSize + " 条），必须有至少 " + MIN_CHARACTERS_RECURRENT + " 个核心人物",
                    "从原文中识别 1~8 个跨集复用的核心角色"));
        } else if (charSize > MAX_CHARACTERS_RECURRENT) {
            issues.add(new SupervisorIssue(
                    "characters_recurrent.size()",
                    SupervisorIssue.WARNING,
                    "characters_recurrent 有 " + charSize + " 条，超过 " + MAX_CHARACTERS_RECURRENT + " 条上限",
                    "合并同名/同义角色，保留核心人物"));
        }

        // 规则 4：world_rules <= 5
        int ruleSize = skeleton.worldRules() == null ? 0 : skeleton.worldRules().size();
        if (ruleSize > MAX_WORLD_RULES) {
            issues.add(new SupervisorIssue(
                    "world_rules.size()",
                    SupervisorIssue.WARNING,
                    "world_rules 有 " + ruleSize + " 条，超过 " + MAX_WORLD_RULES + " 条上限",
                    "保留最重要的硬规则，删除次要的"));
        }

        if (issues.isEmpty()) {
            return SupervisorVerdict.pass();
        }
        return SupervisorVerdict.fail(issues);
    }
}
