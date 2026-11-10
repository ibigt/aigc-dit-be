package cn.aigc.dit.server.drama;

import cn.aigc.dit.server.drama.text.ScriptDoc;
import cn.aigc.dit.server.drama.text.ScriptReferenceIndex;
import cn.aigc.dit.server.drama.text.SemanticNormalizationResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 语义归一化的机械收尾：把模型给的 entity_groups 修成自洽的分组，并把规范名写回剧本。
 *
 * <p><b>为什么需要这一步。</b>语义归一化这一步的产物有两半：模型判定的别名分组
 * （entity_groups）和归一化后的剧本（normalized_script）。判定「这两个 mention 是不是
 * 同一个东西」是语义问题，只能模型判；但把判定结果<b>落到剧本上</b>是机械问题，应该由代码做。
 *
 * <p>14 环境的实测（2026-11-05，MiniMax / series 124 / episode 128）就是栽在这条缝里：
 * 模型其实判对了 —— 它把「众精神病」并进了「众精神病人」那一组，可剧本里还留着
 * 「众精神病」三个字；另一个 mention 又被两个组同时收走。校验器如实报了两条错，
 * 修复轮也没修好，于是整步被判死，编排层回退到<b>原始</b>剧本 —— 原始剧本里
 * 「大号塑料拖鞋 / 大拖鞋」「地藏驱魔符咒 / 地藏驱魔符」「众精神病人 / 众精神病」
 * 三对别名全在，资产页就出现三对重复资产。模型判对的那部分结论被整个丢掉了。
 *
 * <p>所以这里做两件事，都不碰语义：
 * <ol>
 *   <li><b>修分组结构</b>：一个 mention 被多个组收走时归「把它当规范名的那个组」；
 *       没被任何组收走的 mention 自己单成一组；规范名指向不存在的 mention 时
 *       重新锚定到本组第一个成员；组里出现不存在的 mention ID 直接丢掉。</li>
 *   <li><b>把规范名写回剧本</b>：按分组把剧本里的成员写法换成规范名（角色/道具/地点分开换，
 *       一个写法同时指向两个规范名时不换，交给校验器报错）。</li>
 * </ol>
 *
 * <p>这样「模型没改干净」和「模型分组有重叠」这两类毛刺不再把整步判死，
 * 而模型真正的语义判断（谁和谁是一个东西）原样保留。
 */
public final class DramaEntityGroups {

    private DramaEntityGroups() {
    }

    /**
     * 修好分组结构，并把规范名写回 normalized_script。
     *
     * @param result 模型返回的归一化结果（就地修改）
     * @param refs   来源剧本的 mention 索引（mention ID → 类型与原始写法）
     * @return 是否有改动
     */
    public static boolean apply(SemanticNormalizationResult result, ScriptReferenceIndex refs) {
        if (result == null || refs == null) return false;
        Map<String, ScriptReferenceIndex.Mention> mentionById = new LinkedHashMap<>();
        for (ScriptReferenceIndex.Mention mention : refs.mentions()) {
            mentionById.put(mention.id(), mention);
        }
        boolean changed = repairGroups(result, refs, mentionById);
        changed |= applyCanonicalNames(result.getNormalizedScript(), result.getEntityGroups(), mentionById);
        return changed;
    }

    /**
     * 把一组分组里的规范名写进任意一份剧本。
     *
     * <p>归一化那一步没通过校验时，模型改写出来的剧本不能用，但<b>它判出的别名分组</b>通常还是对的 ——
     * 把分组套回原始剧本（原文是完好的），至少同一件东西不会抽出两条资产。
     *
     * @param script 要改写的剧本（就地修改）
     * @param groups 模型判出的分组
     * @param refs   来源剧本的 mention 索引
     * @return 是否有改动
     */
    public static boolean applyCanonicalNames(ScriptDoc script,
                                               List<SemanticNormalizationResult.EntityGroup> groups,
                                               ScriptReferenceIndex refs) {
        if (script == null || script.getScenes() == null || groups == null || refs == null) return false;
        Map<String, ScriptReferenceIndex.Mention> mentionById = new LinkedHashMap<>();
        for (ScriptReferenceIndex.Mention mention : refs.mentions()) {
            mentionById.put(mention.id(), mention);
        }
        return applyCanonicalNames(script, groups, mentionById);
    }

    /**
     * 复制一份剧本，供「把规范名写回去」用 —— 改写不能动到原剧本。
     *
     * <p>只复制会被改写的那层对象（场次本身），台词与动作等字段按引用带过去，
     * 因为改写只碰人物、道具、地点这三样。
     */
    public static ScriptDoc copyForRewrite(ScriptDoc source) {
        ScriptDoc copy = new ScriptDoc();
        if (source == null) return copy;
        copy.setTitle(source.getTitle());
        copy.setLogline(source.getLogline());
        copy.setStylePrefix(source.getStylePrefix());
        List<ScriptDoc.Scene> scenes = new ArrayList<>();
        if (source.getScenes() != null) {
            for (ScriptDoc.Scene scene : source.getScenes()) {
                if (scene == null) continue;
                ScriptDoc.Scene sceneCopy = new ScriptDoc.Scene();
                sceneCopy.setIndex(scene.getIndex());
                sceneCopy.setSlug(scene.getSlug());
                sceneCopy.setHeading(scene.getHeading());
                sceneCopy.setCharacters(new ArrayList<>(scene.getCharacters()));
                sceneCopy.setProps(new ArrayList<>(scene.getProps()));
                sceneCopy.setLocation(scene.getLocation());
                sceneCopy.setTime(scene.getTime());
                sceneCopy.setInterior(scene.getInterior());
                sceneCopy.setAction(scene.getAction());
                sceneCopy.setSound(scene.getSound());
                sceneCopy.setDialogue(scene.getDialogue());
                scenes.add(sceneCopy);
            }
        }
        copy.setScenes(scenes);
        return copy;
    }

    /**
     * 把分组修成「每个 mention 恰好属于一组，每组都有一个合法规范名」。
     */
    private static boolean repairGroups(SemanticNormalizationResult result,
                                         ScriptReferenceIndex refs,
                                         Map<String, ScriptReferenceIndex.Mention> mentionById) {
        List<SemanticNormalizationResult.EntityGroup> source = result.getEntityGroups() == null
                ? List.of() : result.getEntityGroups();

        // 改动前的样子，用来判断有没有真的动过
        Map<SemanticNormalizationResult.EntityGroup, List<String>> originalMembers = new LinkedHashMap<>();
        Map<SemanticNormalizationResult.EntityGroup, String> originalCanonical = new LinkedHashMap<>();
        Map<SemanticNormalizationResult.EntityGroup, String> originalType = new LinkedHashMap<>();
        for (SemanticNormalizationResult.EntityGroup group : source) {
            if (group == null) continue;
            originalMembers.put(group, new ArrayList<>(membersOf(group)));
            originalCanonical.put(group, group.getCanonicalMentionId());
            originalType.put(group, group.getEntityType());
        }

        // 某个 mention 被哪个组当作规范名，就归那个组 —— 它显然认为「这个就是标准写法」。
        Map<String, SemanticNormalizationResult.EntityGroup> canonicalOwner = new LinkedHashMap<>();
        for (SemanticNormalizationResult.EntityGroup group : source) {
            if (group == null) continue;
            String canonicalId = group.getCanonicalMentionId();
            if (canonicalId == null || !mentionById.containsKey(canonicalId)) continue;
            canonicalOwner.putIfAbsent(canonicalId, group);
        }

        List<Resolved> resolved = new ArrayList<>();
        Set<String> taken = new LinkedHashSet<>();
        for (SemanticNormalizationResult.EntityGroup group : source) {
            if (group == null) continue;
            List<String> members = new ArrayList<>();
            for (String id : membersOf(group)) {
                if (!mentionById.containsKey(id)) continue;          // 不存在的 mention ID：丢掉
                if (canonicalOwner.get(id) != null && canonicalOwner.get(id) != group) continue; // 归别人的组
                if (!taken.add(id)) continue;                        // 已被前面的组收走
                members.add(id);
            }
            String canonicalId = group.getCanonicalMentionId();
            if (canonicalId == null || !mentionById.containsKey(canonicalId)
                    || canonicalOwner.get(canonicalId) != group) {
                // 规范名不合法（不存在，或被别的组当规范名拿走了）：重新锚定到本组第一个成员
                canonicalId = members.isEmpty() ? null : members.get(0);
            }
            if (canonicalId == null || members.isEmpty()) {
                // 整组作废：它占下的 mention 交还回去，后面的组或单例组还能用
                taken.removeAll(members);
                resolved.add(null);
                continue;
            }
            if (!members.contains(canonicalId)) {
                members.add(canonicalId);
                taken.add(canonicalId);
            }
            resolved.add(new Resolved(group, members, canonicalId));
        }

        List<SemanticNormalizationResult.EntityGroup> kept = new ArrayList<>();
        Set<String> covered = new LinkedHashSet<>();
        for (Resolved item : resolved) {
            if (item == null) continue;
            SemanticNormalizationResult.EntityGroup group = item.group();
            group.setMemberMentionIds(new ArrayList<>(item.members()));
            group.setCanonicalMentionId(item.canonicalId());
            // 实体类型以来源 mention 为准：模型写错类型是笔误，mention 出自剧本的哪个字段才是事实
            group.setEntityType(mentionById.get(item.canonicalId()).entityType());
            kept.add(group);
            covered.addAll(item.members());
        }
        // 没被任何组收走的 mention：自己单成一组（规范名就是自己，等于「没有别名」）
        for (ScriptReferenceIndex.Mention mention : refs.mentions()) {
            if (covered.contains(mention.id())) continue;
            SemanticNormalizationResult.EntityGroup group = new SemanticNormalizationResult.EntityGroup();
            group.setEntityType(mention.entityType());
            group.setCanonicalMentionId(mention.id());
            group.setMemberMentionIds(new ArrayList<>(List.of(mention.id())));
            group.setConfidence(1.0);
            kept.add(group);
        }
        result.setEntityGroups(kept);
        if (kept.size() != source.size()) return true;
        for (SemanticNormalizationResult.EntityGroup group : kept) {
            if (!Objects.equals(originalMembers.get(group), group.getMemberMentionIds())) return true;
            if (!Objects.equals(originalCanonical.get(group), group.getCanonicalMentionId())) return true;
            if (!Objects.equals(originalType.get(group), group.getEntityType())) return true;
        }
        return false;
    }

    /**
     * 把规范名写回剧本：成员写法一律换成所在组的规范名。
     */
    private static boolean applyCanonicalNames(ScriptDoc script,
                                               List<SemanticNormalizationResult.EntityGroup> groups,
                                               Map<String, ScriptReferenceIndex.Mention> mentionById) {
        if (script == null || script.getScenes() == null) return false;

        // 成员写法 → 规范名。同一写法同时指向两个规范名时判为歧义，不换，交给校验器报错。
        Map<String, String> mapping = new LinkedHashMap<>();
        Set<String> ambiguous = new LinkedHashSet<>();
        for (SemanticNormalizationResult.EntityGroup group : groups) {
            if (group == null) continue;
            ScriptReferenceIndex.Mention canonical = mentionById.get(group.getCanonicalMentionId());
            if (canonical == null) continue;
            String canonicalValue = trim(canonical.value());
            if (canonicalValue.isEmpty()) continue;
            for (String id : membersOf(group)) {
                ScriptReferenceIndex.Mention member = mentionById.get(id);
                if (member == null) continue;
                String value = trim(member.value());
                if (value.isEmpty() || value.equals(canonicalValue)) continue;
                String key = key(canonical.entityType(), value);
                String existing = mapping.get(key);
                if (existing == null) {
                    mapping.put(key, canonicalValue);
                } else if (!existing.equals(canonicalValue)) {
                    mapping.remove(key);
                    ambiguous.add(key);
                }
            }
        }

        boolean changed = false;
        for (ScriptDoc.Scene scene : script.getScenes()) {
            if (scene == null) continue;
            changed |= rewriteList(scene.getCharacters(), ScriptReferenceIndex.TYPE_CHARACTER,
                    mapping, ambiguous, scene::setCharacters);
            changed |= rewriteList(scene.getProps(), ScriptReferenceIndex.TYPE_PROP,
                    mapping, ambiguous, scene::setProps);
            String location = trim(scene.getLocation());
            if (!location.isEmpty()) {
                String rewritten = lookup(ScriptReferenceIndex.TYPE_LOCATION, location, mapping, ambiguous);
                if (rewritten != null && !rewritten.equals(location)) {
                    scene.setLocation(rewritten);
                    changed = true;
                }
            }
        }
        return changed;
    }

    /** 把一列名字换成规范名，换完顺手去掉重复（同一场里不可能有两个同名道具）。 */
    private static boolean rewriteList(List<String> values, String type,
                                       Map<String, String> mapping, Set<String> ambiguous,
                                       java.util.function.Consumer<List<String>> setter) {
        if (values == null || values.isEmpty()) return false;
        List<String> rewritten = new ArrayList<>();
        boolean changed = false;
        for (String value : values) {
            if (value == null) {
                rewritten.add(null);
                continue;
            }
            String mapped = lookup(type, trim(value), mapping, ambiguous);
            if (mapped == null) mapped = value;
            if (!mapped.equals(value)) changed = true;
            if (!contains(rewritten, mapped)) rewritten.add(mapped);
            else changed = true;
        }
        if (changed) setter.accept(rewritten);
        return changed;
    }

    private static String lookup(String type, String value, Map<String, String> mapping, Set<String> ambiguous) {
        if (value == null || value.isEmpty()) return null;
        String key = key(type, value);
        if (ambiguous.contains(key)) return null;
        return mapping.get(key);
    }

    private static boolean contains(List<String> values, String value) {
        for (String item : values) {
            if (Objects.equals(item, value)) return true;
        }
        return false;
    }

    private static String key(String type, String value) {
        return type + "::" + value;
    }

    private static List<String> membersOf(SemanticNormalizationResult.EntityGroup group) {
        List<String> members = group.getMemberMentionIds();
        return members == null ? List.of() : members;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    /** 一组修好之后的形态。 */
    private record Resolved(SemanticNormalizationResult.EntityGroup group, List<String> members, String canonicalId) {
    }
}
