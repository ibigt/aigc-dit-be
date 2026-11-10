package cn.aigc.dit.server.drama.agent.impl;

import cn.aigc.dit.server.drama.agent.AgentContext;
import cn.aigc.dit.server.drama.agent.ScriptParseReq;
import cn.aigc.dit.server.drama.agent.ScriptSupervisor;
import cn.aigc.dit.server.drama.agent.SupervisorIssue;
import cn.aigc.dit.server.drama.agent.SupervisorVerdict;
import cn.aigc.dit.server.drama.text.ScriptDoc;
import cn.aigc.dit.server.drama.text.ScriptParser;
import cn.aigc.dit.server.drama.text.SkeletonDoc;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ScriptSupervisor 实现：纯本地确定性校验，无 LLM 调用。
 */
@Component
public class ScriptSupervisorImpl implements ScriptSupervisor {

    /** 单场最多道具数。 */
    private static final int MAX_PROPS_PER_SCENE = 3;
    /** scene 数量与骨架 scenes_hint 偏差上限。 */
    private static final int MAX_SCENE_COUNT_DRIFT = 2;
    /** dialogue 子串匹配时的最小长度（避免过短匹配误报）。 */
    private static final int MIN_DIALOGUE_MATCH_LENGTH = 4;

    @Override
    public SupervisorVerdict validate(AgentContext ctx, ScriptDoc script, Object originalInput) {
        List<SupervisorIssue> issues = new ArrayList<>();
        ScriptParseReq req = (originalInput instanceof ScriptParseReq r) ? r : null;
        SkeletonDoc skeleton = req == null ? null : req.skeleton();
        String raw = req == null ? null : req.raw();

        if (script == null || script.getScenes() == null || script.getScenes().isEmpty()) {
            issues.add(new SupervisorIssue(
                    "scenes",
                    SupervisorIssue.ERROR,
                    "scenes 为空，未生成分场",
                    "请确保至少生成 1 个 scene"));
            return SupervisorVerdict.fail(issues);
        }

        // 规则 1：characters_recurrent 强制覆盖
        if (skeleton != null && skeleton.charactersRecurrent() != null) {
            Set<String> appeared = new HashSet<>();
            for (ScriptDoc.Scene s : script.getScenes()) {
                if (s.getCharacters() != null) appeared.addAll(s.getCharacters());
            }
            for (String name : skeleton.charactersRecurrent()) {
                if (!appeared.contains(name)) {
                    issues.add(new SupervisorIssue(
                            "characters_recurrent[" + name + "]",
                            SupervisorIssue.ERROR,
                            "跨集人物 \"" + name + "\" 在本集未出场",
                            "建议在某个 scene.characters 中加入 \"" + name + "\"，并安排台词或动作"));
                }
            }
        }

        // 规则 2：每场 props <= 3
        // 规则 3：每场 characters >= 1
        for (int i = 0; i < script.getScenes().size(); i++) {
            ScriptDoc.Scene scene = script.getScenes().get(i);
            int propsSize = scene.getProps() == null ? 0 : scene.getProps().size();
            int charSize = scene.getCharacters() == null ? 0 : scene.getCharacters().size();

            if (propsSize > MAX_PROPS_PER_SCENE) {
                issues.add(new SupervisorIssue(
                        "scenes[" + i + "].props.size()",
                        SupervisorIssue.WARNING,
                        "第 " + scene.getIndex() + " 场有 " + propsSize + " 个道具，超过 " + MAX_PROPS_PER_SCENE + " 个上限",
                        "保留最重要的 " + MAX_PROPS_PER_SCENE + " 个，删除次要道具"));
            }
            if (charSize == 0) {
                issues.add(new SupervisorIssue(
                        "scenes[" + i + "].characters.size()",
                        SupervisorIssue.ERROR,
                        "第 " + scene.getIndex() + " 场 characters 为空",
                        "必须列出本场出场与说话的所有人物"));
            }
        }

        // 规则 4：dialogue 严格保留原文
        if (raw != null) {
            for (int i = 0; i < script.getScenes().size(); i++) {
                ScriptDoc.Scene scene = script.getScenes().get(i);
                if (scene.getDialogue() == null) continue;
                for (int j = 0; j < scene.getDialogue().size(); j++) {
                    ScriptDoc.Dialogue d = scene.getDialogue().get(j);
                    String line = d.getLine();
                    if (line == null || line.isBlank()) {
                        issues.add(new SupervisorIssue(
                                "scenes[" + i + "].dialogue[" + j + "].line",
                                SupervisorIssue.ERROR,
                                "台词为空",
                                "dialogue.line 必须保留原文"));
                        continue;
                    }
                    String trimmed = line.strip();
                    if (trimmed.length() >= MIN_DIALOGUE_MATCH_LENGTH
                            && !raw.contains(trimmed)) {
                        issues.add(new SupervisorIssue(
                                "scenes[" + i + "].dialogue[" + j + "].line",
                                SupervisorIssue.ERROR,
                                "台词 \"" + (trimmed.length() > 20 ? trimmed.substring(0, 20) + "…" : trimmed) + "\" 在原文中找不到",
                                "必须从原文逐字保留，不得改写或补全"));
                    }
                }
            }
        }

        // 规则 5：场次数 —— 原文有明确场标记时以原文为准，否则对齐骨架规划
        List<ScriptParser.SceneMarker> markers = ScriptParser.detectSceneMarkers(raw);
        if (!markers.isEmpty()) {
            checkSceneAnchors(issues, script, markers);
        } else if (skeleton != null && skeleton.episodes() != null && !skeleton.episodes().isEmpty()) {
            int expectedHint = skeleton.episodes().get(0).scenesHint() == null
                    ? 0 : skeleton.episodes().get(0).scenesHint().size();
            int actualScene = script.getScenes().size();
            int drift = Math.abs(actualScene - expectedHint);
            if (expectedHint > 0 && drift > MAX_SCENE_COUNT_DRIFT) {
                issues.add(new SupervisorIssue(
                        "scenes.size()",
                        SupervisorIssue.WARNING,
                        "本集 " + actualScene + " 个 scene，与骨架规划 " + expectedHint + " 个偏差 " + drift + " 场（上限 " + MAX_SCENE_COUNT_DRIFT + "）",
                        "调整 scene 数量使偏差 ≤ " + MAX_SCENE_COUNT_DRIFT));
            }
        }

        if (issues.isEmpty()) {
            return SupervisorVerdict.pass();
        }
        return SupervisorVerdict.fail(issues);
    }

    /**
     * 原文场次锚点校验。
     *
     * <p>用户自己写了几个场，就是几场：漏场是硬错误（会让模型重跑一次把漏掉的补回来），
     * 多拆只是提醒（同一场里换了独立地点时允许拆）。锚点里的地点还要能在某一场上找到，
     * 找不到说明模型把地名改写了，提醒但不阻塞。
     */
    private void checkSceneAnchors(List<SupervisorIssue> issues, ScriptDoc script,
                                   List<ScriptParser.SceneMarker> markers) {
        int expected = markers.size();
        int actual = script.getScenes().size();
        if (actual < expected) {
            issues.add(new SupervisorIssue(
                    "scenes.size()",
                    SupervisorIssue.ERROR,
                    "原文有 " + expected + " 个场次标记，剧本只拆出 " + actual + " 场，漏掉了 " + (expected - actual) + " 场",
                    "每个场次标记对应一个 scene，不得把两场合并成一场，也不得漏场"));
        } else if (actual > expected) {
            issues.add(new SupervisorIssue(
                    "scenes.size()",
                    SupervisorIssue.WARNING,
                    "原文有 " + expected + " 个场次标记，剧本拆出 " + actual + " 场，多出 " + (actual - expected) + " 场",
                    "除非同一场里确实换了独立地点需要拆场，否则应与原文场次一一对应"));
        }
        for (ScriptParser.SceneMarker marker : markers) {
            String place = ScriptParser.headingPlace(marker.heading());
            if (place.isBlank() || coveredByScenes(place, script)) {
                continue;
            }
            issues.add(new SupervisorIssue(
                    "scenes[" + marker.index() + "].location",
                    SupervisorIssue.WARNING,
                    "原文第 " + marker.index() + " 场「" + marker.heading() + "」的地点「" + place + "」没有出现在任何一场的 location 里",
                    "本场 location 写原文这个地点，不要换成别的名字"));
        }
    }

    /** 这处地点有没有被某一场认领（允许模型给地点加了修饰词）。 */
    private boolean coveredByScenes(String place, ScriptDoc script) {
        for (ScriptDoc.Scene scene : script.getScenes()) {
            if (scene == null) {
                continue;
            }
            String location = scene.getLocation();
            if (location != null && !location.isBlank()) {
                if (location.contains(place)) {
                    return true;
                }
                // 模型把地名写长了（「冷宫」写成「冷宫偏殿」也算认领）。
                if (location.length() >= 2 && place.contains(location)) {
                    return true;
                }
            }
            String heading = scene.getHeading();
            if (heading != null && heading.contains(place)) {
                return true;
            }
        }
        return false;
    }
}
