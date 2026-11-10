package cn.aigc.dit.server.drama.agent.impl;

import cn.aigc.dit.server.drama.agent.AgentContext;
import cn.aigc.dit.server.drama.agent.ShotDraftReq;
import cn.aigc.dit.server.drama.agent.ShotSupervisor;
import cn.aigc.dit.server.drama.agent.SupervisorIssue;
import cn.aigc.dit.server.drama.agent.SupervisorVerdict;
import cn.aigc.dit.server.drama.text.ScriptDoc;
import cn.aigc.dit.server.drama.text.ShotDraftList;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ShotSupervisor 实现：纯本地校验。
 */
@Component
public class ShotSupervisorImpl implements ShotSupervisor {

    /** 镜头时长下限。 */
    private static final int MIN_DURATION_SEC = 5;
    /** 镜头时长上限。 */
    private static final int MAX_DURATION_SEC = 15;
    /** 单场对白数 = 同 chapter 的合理 shot 数上界（1 镜可演 ≈ 1 条对白）。 */
    private static final int MAX_SHOTS_PER_DIALOGUE_RATIO = 2;

    @Override
    public SupervisorVerdict validate(AgentContext ctx, ShotDraftList shots, Object originalInput) {
        List<SupervisorIssue> issues = new ArrayList<>();
        ShotDraftReq req = (originalInput instanceof ShotDraftReq r) ? r : null;
        ScriptDoc script = req == null ? null : req.script();

        if (shots == null || shots.getShots() == null || shots.getShots().isEmpty()) {
            issues.add(new SupervisorIssue(
                    "shots.size()",
                    SupervisorIssue.ERROR,
                    "shots 为空，未生成分镜",
                    "至少生成 1 个 shot"));
            return SupervisorVerdict.fail(issues);
        }

        // 收集 ScriptDoc 所有对白（用于 substring 匹配）
        String allDialogue = script == null ? "" : concatAllDialogue(script);

        // 按 chapter_index 分组
        Map<Integer, Integer> shotsPerChapter = new HashMap<>();

        for (int i = 0; i < shots.getShots().size(); i++) {
            ShotDraftList.ShotDraft s = shots.getShots().get(i);
            int idx = i;

            // 规则 1：duration ∈ [5, 15]
            Integer dur = s.getDurationSec();
            if (dur == null) {
                issues.add(new SupervisorIssue(
                        "shots[" + idx + "].duration_sec",
                        SupervisorIssue.ERROR,
                        "shot " + s.getIndex() + " 时长为空",
                        "duration_sec 必须是 5~15 的整数"));
            } else if (dur < MIN_DURATION_SEC || dur > MAX_DURATION_SEC) {
                issues.add(new SupervisorIssue(
                        "shots[" + idx + "].duration_sec",
                        SupervisorIssue.ERROR,
                        "shot " + s.getIndex() + " 时长 " + dur + "s，超出 [" + MIN_DURATION_SEC + ", " + MAX_DURATION_SEC + "] 区间",
                        "调整 duration_sec 到区间内"));
            }

            // 规则 2：dialogue 在 ScriptDoc 中能找
            String dialogue = s.getDialogue();
            if (dialogue != null && !dialogue.isBlank() && !allDialogue.isEmpty()) {
                String trimmed = dialogue.strip();
                if (trimmed.length() >= 4 && !allDialogue.contains(trimmed)) {
                    issues.add(new SupervisorIssue(
                            "shots[" + idx + "].dialogue",
                            SupervisorIssue.WARNING,
                            "shot " + s.getIndex() + " dialogue \"" + truncate(trimmed, 20)
                                    + "\" 在 ScriptDoc 对白中找不到",
                            "dialogue 必须从 ScriptDoc 原样引用，不得自创"));
                }
            }

            // 规则 3：cast_count == character_names.size
            int charSize = s.getCharacterNames() == null ? 0 : s.getCharacterNames().size();
            Integer cast = s.getCastCount();
            if (cast != null && cast != charSize) {
                issues.add(new SupervisorIssue(
                        "shots[" + idx + "].cast_count",
                        SupervisorIssue.WARNING,
                        "shot " + s.getIndex() + " cast_count=" + cast + " 与 character_names.size=" + charSize + " 不一致",
                        "cast_count 必须等于 character_names.size"));
            }

            // 累计每章 shot 数
            if (s.getChapterIndex() != null) {
                shotsPerChapter.merge(s.getChapterIndex(), 1, Integer::sum);
            }
        }

        // 规则 4：每章 shot 数不超过 ScriptDoc 对白数 × 比例上界
        if (script != null && script.getScenes() != null) {
            for (ScriptDoc.Scene scene : script.getScenes()) {
                int dialogueCount = scene.getDialogue() == null ? 0 : scene.getDialogue().size();
                int chapterShots = shotsPerChapter.getOrDefault(scene.getIndex(), 0);
                if (dialogueCount > 0 && chapterShots > dialogueCount * MAX_SHOTS_PER_DIALOGUE_RATIO) {
                    issues.add(new SupervisorIssue(
                            "chapter[" + scene.getIndex() + "].shots.size()",
                            SupervisorIssue.WARNING,
                            "第 " + scene.getIndex() + " 场（" + dialogueCount + " 条对白）生成了 " + chapterShots + " 个镜头，超出合理上界 " + (dialogueCount * MAX_SHOTS_PER_DIALOGUE_RATIO),
                            "合并镜头：连续对白尽量放在同一镜"));
                }
            }
        }

        if (issues.isEmpty()) {
            return SupervisorVerdict.pass();
        }
        return SupervisorVerdict.fail(issues);
    }

    private String concatAllDialogue(ScriptDoc script) {
        StringBuilder sb = new StringBuilder();
        for (ScriptDoc.Scene scene : script.getScenes()) {
            if (scene.getDialogue() == null) continue;
            for (ScriptDoc.Dialogue d : scene.getDialogue()) {
                if (d.getLine() != null) sb.append(d.getLine()).append("\n");
            }
        }
        return sb.toString();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
