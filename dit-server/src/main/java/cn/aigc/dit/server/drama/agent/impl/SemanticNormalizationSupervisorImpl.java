package cn.aigc.dit.server.drama.agent.impl;

import cn.aigc.dit.server.drama.DramaPlaceNames;
import cn.aigc.dit.server.drama.agent.AgentContext;
import cn.aigc.dit.server.drama.agent.SemanticNormalizationReq;
import cn.aigc.dit.server.drama.agent.SemanticNormalizationSupervisor;
import cn.aigc.dit.server.drama.agent.SupervisorIssue;
import cn.aigc.dit.server.drama.agent.SupervisorVerdict;
import cn.aigc.dit.server.drama.text.ScriptDoc;
import cn.aigc.dit.server.drama.text.ScriptReferenceIndex;
import cn.aigc.dit.server.drama.text.SemanticNormalizationResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Validates source integrity only; all semantic grouping decisions remain model-owned. */
@Component
public class SemanticNormalizationSupervisorImpl implements SemanticNormalizationSupervisor {

    @Override
    public SupervisorVerdict validate(AgentContext ctx, SemanticNormalizationResult output, Object originalInput) {
        List<SupervisorIssue> issues = new ArrayList<>();
        if (!(originalInput instanceof SemanticNormalizationReq req)) {
            return fail("request", "缺少语义归一化原始请求");
        }
        if (output == null || output.getNormalizedScript() == null) {
            return fail("normalized_script", "normalized_script 为空");
        }
        ScriptReferenceIndex refs = req.references() == null
                ? ScriptReferenceIndex.from(req.script()) : req.references();

        Map<String, ScriptReferenceIndex.Mention> mentionById = new LinkedHashMap<>();
        for (ScriptReferenceIndex.Mention mention : refs.mentions()) {
            mentionById.put(mention.id(), mention);
        }
        validateGroups(output, mentionById, issues);
        validateSceneIndexes(output.getNormalizedScript(), output.getSceneLineage(), issues);
        validateCanonicalVocabulary(output, mentionById, issues);
        validateLineageSources(output, refs, issues);
        validateDuplicatePlaces(output, mentionById, issues);

        return issues.isEmpty() ? SupervisorVerdict.pass() : SupervisorVerdict.fail(issues);
    }

    /**
     * 同一处地方不能有两个规范地点名。
     *
     * <p>场景资产是按规范地点逐条抽的：一个地点写成「皇后的风仪宫，未央宫」，而「未央宫」
     * 本身又是另一场的地点，归一化后就会留下两个规范地点，页面上出现两张讲同一个地方的
     * 场景设定图。这里机械地查一遍「一条规范地点并列了另一条规范地点」的情况。
     *
     * <p>只报 warning：这条规则要判断的是语义（这一场到底发生在哪一处），模型判错时
     * 不该把整个归一化步骤判死 —— 判死会回退到原始 ScriptDoc，反而更糟。
     */
    private void validateDuplicatePlaces(SemanticNormalizationResult output,
                                          Map<String, ScriptReferenceIndex.Mention> mentionById,
                                          List<SupervisorIssue> issues) {
        List<String> locations = new ArrayList<>();
        for (SemanticNormalizationResult.EntityGroup group : safe(output.getEntityGroups())) {
            if (group == null) continue;
            ScriptReferenceIndex.Mention canonical = mentionById.get(group.getCanonicalMentionId());
            if (canonical == null) continue;
            if (!ScriptReferenceIndex.TYPE_LOCATION.equals(canonical.entityType())) continue;
            String value = canonical.value() == null ? "" : canonical.value().trim();
            if (!value.isEmpty() && !locations.contains(value)) {
                locations.add(value);
            }
        }
        for (String outer : locations) {
            for (String part : DramaPlaceNames.splitEnumeration(outer)) {
                if (part.equals(outer) || !locations.contains(part)) {
                    continue;
                }
                warn(issues, "entity_groups.canonical_mention_id",
                        "两个规范地点讲的是同一处地方: 「" + outer + "」并列了「" + part + "」。"
                                + "场景资产按规范地点抽取，同一处地方不能留两个规范名，"
                                + "否则页面上会出现两张讲同一个地方的场景设定图");
            }
        }
    }

    private void validateGroups(SemanticNormalizationResult output,
                                Map<String, ScriptReferenceIndex.Mention> mentionById,
                                List<SupervisorIssue> issues) {
        Map<String, Integer> coverage = new HashMap<>();
        List<SemanticNormalizationResult.EntityGroup> groups = safe(output.getEntityGroups());
        for (int i = 0; i < groups.size(); i++) {
            SemanticNormalizationResult.EntityGroup group = groups.get(i);
            String path = "entity_groups[" + i + "]";
            if (group == null) {
                issue(issues, path, "分组为空");
                continue;
            }
            List<String> members = safe(group.getMemberMentionIds());
            Set<String> local = new HashSet<>();
            for (String id : members) {
                coverage.merge(id, 1, Integer::sum);
                if (!local.add(id)) {
                    issue(issues, path + ".member_mention_ids", "同一 mention ID 重复出现: " + id);
                }
                ScriptReferenceIndex.Mention mention = mentionById.get(id);
                if (mention == null) {
                    issue(issues, path + ".member_mention_ids", "引用不存在的 mention ID: " + id);
                } else if (!Objects.equals(group.getEntityType(), mention.entityType())) {
                    issue(issues, path + ".entity_type",
                            "分组类型与来源 mention 类型不一致: " + id + "（该 mention 的 entity_type 是「"
                                    + mention.entityType() + "」，分组必须逐字使用它）");
                }
            }
            String canonicalId = group.getCanonicalMentionId();
            if (!mentionById.containsKey(canonicalId)) {
                issue(issues, path + ".canonical_mention_id", "规范 mention ID 不存在: " + canonicalId);
            } else if (!members.contains(canonicalId)) {
                issue(issues, path + ".canonical_mention_id", "规范 mention ID 必须属于本组: " + canonicalId);
            }
        }
        for (String id : mentionById.keySet()) {
            int count = coverage.getOrDefault(id, 0);
            if (count != 1) {
                issue(issues, "entity_groups.member_mention_ids",
                        "mention ID 必须且只能覆盖一次: " + id + "，实际=" + count);
            }
        }
    }

    private void validateSceneIndexes(ScriptDoc script,
                                      List<SemanticNormalizationResult.SceneLineage> lineage,
                                      List<SupervisorIssue> issues) {
        List<ScriptDoc.Scene> scenes = safe(script.getScenes());
        for (int i = 0; i < scenes.size(); i++) {
            ScriptDoc.Scene scene = scenes.get(i);
            if (scene == null) {
                issue(issues, "normalized_script.scenes[" + i + "].index",
                        "第 " + (i + 1) + " 个归一化场次为空");
            } else if (!Objects.equals(scene.getIndex(), i + 1)) {
                issue(issues, "normalized_script.scenes[" + i + "].index",
                        "第 " + (i + 1) + " 个归一化场次的 index 必须是 " + (i + 1)
                                + "（scenes 数组顺序即归一化场次顺序，从 1 连续递增），实际="
                                + scene.getIndex());
            }
        }
        Map<Integer, Integer> lineageCoverage = new HashMap<>();
        for (SemanticNormalizationResult.SceneLineage item : safe(lineage)) {
            if (item != null && item.getNormalizedSceneIndex() != null) {
                lineageCoverage.merge(item.getNormalizedSceneIndex(), 1, Integer::sum);
            }
        }
        for (int i = 1; i <= scenes.size(); i++) {
            if (lineageCoverage.getOrDefault(i, 0) != 1) {
                issue(issues, "scene_lineage", "归一化场次 " + i + " 必须且只能有一条 lineage");
            }
        }
    }

    private void validateCanonicalVocabulary(SemanticNormalizationResult output,
                                             Map<String, ScriptReferenceIndex.Mention> mentionById,
                                             List<SupervisorIssue> issues) {
        Map<String, Set<String>> canonicalByType = new HashMap<>();
        for (SemanticNormalizationResult.EntityGroup group : safe(output.getEntityGroups())) {
            if (group == null) continue;
            ScriptReferenceIndex.Mention canonical = mentionById.get(group.getCanonicalMentionId());
            if (canonical != null) {
                canonicalByType.computeIfAbsent(canonical.entityType(), key -> new LinkedHashSet<>())
                        .add(canonical.value());
            }
        }
        List<ScriptDoc.Scene> scenes = safe(output.getNormalizedScript().getScenes());
        Map<String, Set<String>> actualByType = new HashMap<>();
        for (int i = 0; i < scenes.size(); i++) {
            ScriptDoc.Scene scene = scenes.get(i);
            if (scene == null) continue;
            actualByType.computeIfAbsent(ScriptReferenceIndex.TYPE_CHARACTER, key -> new LinkedHashSet<>())
                    .addAll(safe(scene.getCharacters()));
            actualByType.computeIfAbsent(ScriptReferenceIndex.TYPE_PROP, key -> new LinkedHashSet<>())
                    .addAll(safe(scene.getProps()));
            if (hasText(scene.getLocation())) {
                actualByType.computeIfAbsent(ScriptReferenceIndex.TYPE_LOCATION, key -> new LinkedHashSet<>())
                        .add(scene.getLocation());
            }
            validateNames(scene.getCharacters(), ScriptReferenceIndex.TYPE_CHARACTER,
                    "normalized_script.scenes[" + i + "].characters", canonicalByType, issues);
            validateNames(scene.getProps(), ScriptReferenceIndex.TYPE_PROP,
                    "normalized_script.scenes[" + i + "].props", canonicalByType, issues);
            if (hasText(scene.getLocation())
                    && !canonicalByType.getOrDefault(ScriptReferenceIndex.TYPE_LOCATION, Set.of())
                    .contains(scene.getLocation())) {
                issue(issues, "normalized_script.scenes[" + i + "].location",
                        "地点不是来源 mention 选出的规范名: " + scene.getLocation()
                                + "。可用地点=" + canonicalCandidates(canonicalByType, ScriptReferenceIndex.TYPE_LOCATION)
                                + "；拆场时新场次必须沿用其来源场次的地点，不得发明来源里没有的地点名");
            }
        }
        for (Map.Entry<String, Set<String>> expected : canonicalByType.entrySet()) {
            Set<String> actual = actualByType.getOrDefault(expected.getKey(), Set.of());
            for (String canonicalName : expected.getValue()) {
                if (!actual.contains(canonicalName)) {
                    issue(issues, "normalized_script",
                            "规范实体未出现在 normalized_script: " + expected.getKey() + "/" + canonicalName
                                    + "。请把「" + canonicalName + "」写回某个归一化场次的 "
                                    + fieldNameOf(expected.getKey())
                                    + " 列表（不要删除该 entity_group：每个来源 mention ID 必须且只能被覆盖一次）");
                }
            }
        }
    }

    /** 规范名候选列表，直接喂给模型当「合法取值」用。 */
    private String canonicalCandidates(Map<String, Set<String>> canonicalByType, String type) {
        Set<String> names = canonicalByType.getOrDefault(type, Set.of());
        return names.isEmpty() ? "（无）" : String.join("、", names);
    }

    /** entity_type → 归一化场次里对应的字段名。 */
    private String fieldNameOf(String entityType) {
        return switch (entityType) {
            case ScriptReferenceIndex.TYPE_CHARACTER -> "characters";
            case ScriptReferenceIndex.TYPE_PROP -> "props";
            case ScriptReferenceIndex.TYPE_LOCATION -> "location";
            default -> entityType;
        };
    }

    private void validateNames(List<String> names, String type, String path,
                               Map<String, Set<String>> canonicalByType,
                               List<SupervisorIssue> issues) {
        Set<String> allowed = canonicalByType.getOrDefault(type, Set.of());
        for (String name : safe(names)) {
            if (!allowed.contains(name)) {
                issue(issues, path, "名称不是来源 mention 选出的规范名: " + name);
            }
        }
    }

    private void validateLineageSources(SemanticNormalizationResult output,
                                        ScriptReferenceIndex refs,
                                        List<SupervisorIssue> issues) {
        Map<String, ScriptReferenceIndex.ActionSource> actionById = new LinkedHashMap<>();
        refs.actions().forEach(source -> actionById.put(source.id(), source));
        Map<String, ScriptReferenceIndex.DialogueSource> dialogueById = new LinkedHashMap<>();
        refs.dialogues().forEach(source -> dialogueById.put(source.id(), source));
        Map<String, StringBuilder> actionParts = new LinkedHashMap<>();
        Map<String, Integer> dialogueCoverage = new LinkedHashMap<>();

        Map<Integer, SemanticNormalizationResult.SceneLineage> lineageByScene = new HashMap<>();
        for (SemanticNormalizationResult.SceneLineage item : safe(output.getSceneLineage())) {
            if (item != null && item.getNormalizedSceneIndex() != null) {
                lineageByScene.putIfAbsent(item.getNormalizedSceneIndex(), item);
            }
        }
        List<ScriptDoc.Scene> scenes = safe(output.getNormalizedScript().getScenes());
        for (int scenePos = 0; scenePos < scenes.size(); scenePos++) {
            ScriptDoc.Scene scene = scenes.get(scenePos);
            SemanticNormalizationResult.SceneLineage lineage = lineageByScene.get(scenePos + 1);
            if (scene == null || lineage == null) continue;
            Set<Integer> sourceScenes = new HashSet<>(safe(lineage.getSourceSceneIndexes()));
            for (Integer sourceScene : sourceScenes) {
                if (sourceScene == null || sourceScene < 0) {
                    issue(issues, "scene_lineage[" + scenePos + "].source_scene_indexes",
                            "来源场次索引无效: " + sourceScene);
                }
            }

            List<String> actionIds = safe(lineage.getActionSourceIds());
            if (hasText(scene.getAction()) && actionIds.size() != 1) {
                issue(issues, "scene_lineage[" + scenePos + "].action_source_ids",
                        "有 action 的归一化场次必须引用一个来源 action（当前引用了 " + actionIds.size()
                                + " 个）。可用来源 action=" + idsOfScene(refs.actions(), sourceScenes, ScriptReferenceIndex.ActionSource::id, ScriptReferenceIndex.ActionSource::sourceSceneIndex));
            }
            for (String id : actionIds) {
                ScriptReferenceIndex.ActionSource source = actionById.get(id);
                if (source == null) {
                    issue(issues, "scene_lineage[" + scenePos + "].action_source_ids",
                            "来源 action ID 不存在: " + id + "。可用来源 action="
                                    + idsOfScene(refs.actions(), sourceScenes, ScriptReferenceIndex.ActionSource::id, ScriptReferenceIndex.ActionSource::sourceSceneIndex));
                } else if (!sourceScenes.contains(source.sourceSceneIndex())) {
                    issue(issues, "scene_lineage[" + scenePos + "].action_source_ids",
                            "action 来源场次未列入 source_scene_indexes: " + id
                                    + "（该 action 属于来源场次 " + source.sourceSceneIndex()
                                    + "，source_scene_indexes 必须包含它）");
                } else if (hasText(scene.getAction())) {
                    actionParts.computeIfAbsent(id, key -> new StringBuilder()).append(scene.getAction());
                }
            }

            List<String> dialogueIds = safe(lineage.getDialogueSourceIds());
            List<ScriptDoc.Dialogue> normalizedDialogues = safe(scene.getDialogue());
            if (dialogueIds.size() != normalizedDialogues.size()) {
                issue(issues, "scene_lineage[" + scenePos + "].dialogue_source_ids",
                        "dialogue 引用数量与归一化 dialogue 数量不一致");
            }
            int paired = Math.min(dialogueIds.size(), normalizedDialogues.size());
            for (int i = 0; i < dialogueIds.size(); i++) {
                String id = dialogueIds.get(i);
                dialogueCoverage.merge(id, 1, Integer::sum);
                ScriptReferenceIndex.DialogueSource source = dialogueById.get(id);
                if (source == null) {
                    issue(issues, "scene_lineage[" + scenePos + "].dialogue_source_ids",
                            "来源 dialogue ID 不存在: " + id + "。可用来源 dialogue="
                                    + idsOfScene(refs.dialogues(), sourceScenes, ScriptReferenceIndex.DialogueSource::id, ScriptReferenceIndex.DialogueSource::sourceSceneIndex));
                } else if (!sourceScenes.contains(source.sourceSceneIndex())) {
                    issue(issues, "scene_lineage[" + scenePos + "].dialogue_source_ids",
                            "dialogue 来源场次未列入 source_scene_indexes: " + id
                                    + "（该 dialogue 属于来源场次 " + source.sourceSceneIndex()
                                    + "，source_scene_indexes 必须包含它）");
                } else if (i < paired) {
                    String difference = dialogueDifference(source, normalizedDialogues.get(i));
                    if (difference != null) {
                        issue(issues, "scene_lineage[" + scenePos + "].dialogue_source_ids",
                                "dialogue 内容或顺序被修改: " + id + " —— " + difference
                                        + "。必须逐字复制来源，不要改标点、空白或情绪");
                    }
                }
            }
        }
        for (ScriptReferenceIndex.ActionSource source : refs.actions()) {
            String rebuilt = actionParts.getOrDefault(source.id(), new StringBuilder()).toString();
            String expected = normalizeWhitespace(source.value());
            String actual = normalizeWhitespace(rebuilt);
            if (!expected.equals(actual)) {
                issue(issues, "scene_lineage.action_source_ids",
                        "action 未被完整有序保留: " + source.id()
                                + " —— 归一化后拼接长度 " + actual.length() + " 字，来源长度 " + expected.length()
                                + " 字，首个差异位置 " + firstDifference(expected, actual)
                                + "。必须按来源原顺序切成精确子串，不得改写、概括、漏字或补字");
            }
        }
        for (String id : dialogueById.keySet()) {
            if (dialogueCoverage.getOrDefault(id, 0) != 1) {
                issue(issues, "scene_lineage.dialogue_source_ids",
                        "dialogue 必须且只能保留一次: " + id);
            }
        }
    }

    private boolean sameDialogue(ScriptReferenceIndex.DialogueSource source, ScriptDoc.Dialogue normalized) {
        return dialogueDifference(source, normalized) == null;
    }

    /**
     * 返回 dialogue 与来源的差异描述；无差异返回 null。
     * 差异必须精确到字段并给出期望值/实际值，否则模型无法定位、只能瞎改。
     */
    private String dialogueDifference(ScriptReferenceIndex.DialogueSource source, ScriptDoc.Dialogue normalized) {
        if (normalized == null) {
            return "归一化 dialogue 为 null";
        }
        if (!Objects.equals(source.speaker(), normalized.getSpeaker())) {
            return "speaker 期望「" + source.speaker() + "」实际「" + normalized.getSpeaker() + "」";
        }
        if (!Objects.equals(source.line(), normalized.getLine())) {
            return "line 期望「" + source.line() + "」实际「" + normalized.getLine() + "」";
        }
        if (!Objects.equals(source.emotion(), normalized.getEmotion())) {
            return "emotion 期望「" + source.emotion() + "」实际「" + normalized.getEmotion() + "」";
        }
        return null;
    }

    private String normalizeWhitespace(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
    }

    /** 两段文本的首个差异位置（-1 表示一方是另一方的前缀）。 */
    private int firstDifference(String expected, String actual) {
        int limit = Math.min(expected.length(), actual.length());
        for (int i = 0; i < limit; i++) {
            if (expected.charAt(i) != actual.charAt(i)) {
                return i;
            }
        }
        return limit;
    }

    /** 列出属于给定来源场次集合的引用 ID，直接喂给模型当「合法取值」用。 */
    private <T> String idsOfScene(List<T> sources, Set<Integer> sceneIndexes,
                                  java.util.function.Function<T, String> idOf,
                                  java.util.function.Function<T, Integer> sceneOf) {
        List<String> ids = sources.stream()
                .filter(source -> sceneIndexes.contains(sceneOf.apply(source)))
                .map(idOf)
                .toList();
        return ids.isEmpty() ? "（无）" : String.join("、", ids);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private void issue(List<SupervisorIssue> issues, String field, String message) {
        issues.add(new SupervisorIssue(field, SupervisorIssue.ERROR, message,
                "仅修复引用覆盖和原文完整性；不要改变语义判断"));
    }

    /** 语义类问题：只提醒，不判死。 */
    private void warn(List<SupervisorIssue> issues, String field, String message) {
        issues.add(new SupervisorIssue(field, SupervisorIssue.WARNING, message,
                "判断这一场实际发生在哪一处：把并列的地点 mention 并入那一处的分组，"
                        + "并把该场的 location 改成那个规范名；如果这一场确实发生在两处，"
                        + "就拆成两场，每场一个地点"));
    }

    private SupervisorVerdict fail(String field, String message) {
        return SupervisorVerdict.fail(List.of(new SupervisorIssue(
                field, SupervisorIssue.ERROR, message, "返回完整语义归一化合同")));
    }
}
