package cn.aigc.dit.server.drama.text;

import cn.aigc.dit.server.drama.DramaStoryboardCompiler;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 分镜视觉扩写的输出合同与合并校验。
 * 只允许改写 narrative_goal / scene 两个字段；任何破坏人物锚点、引入本镜之外的名字、
 * 塞进台词或长度失控的条目整镜回退原稿——扩写永远不让拆镜结果变差。
 */
public final class DramaShotEnrichment {

    /** 硬上限；提示词目标 narrative_goal 60~150 / scene 40~90，超限说明模型失控，整镜回退。 */
    static final int MAX_NARRATIVE_GOAL_LENGTH = 200;
    static final int MAX_SCENE_LENGTH = 120;

    private DramaShotEnrichment() {
    }

    @Data
    public static class ShotEnrichList {
        private List<ShotEnrich> shots;
    }

    @Data
    public static class ShotEnrich {
        @JsonProperty("chapter_index")
        @JsonAlias("chapterIndex")
        private Integer chapterIndex;

        private Integer index;

        private String scene;

        @JsonProperty("narrative_goal")
        @JsonAlias("narrativeGoal")
        private String narrativeGoal;
    }

    /**
     * 把扩写结果合并回草稿，返回成功替换的字段数（一镜全成 = 2）。
     * 键对不上或校验不过的条目直接丢弃，保留原稿。
     */
    public static int merge(ShotDraftList target, ShotEnrichList enriched, Set<String> assetNames) {
        if (target == null || enriched == null
                || target.getShots() == null || enriched.getShots() == null) {
            return 0;
        }
        Map<String, ShotDraftList.ShotDraft> byKey = new HashMap<>();
        for (ShotDraftList.ShotDraft shot : target.getShots()) {
            if (shot != null && shot.getChapterIndex() != null && shot.getIndex() != null) {
                byKey.put(shot.getChapterIndex() + "\0" + shot.getIndex(), shot);
            }
        }
        Set<String> knownNames = assetNames == null ? Set.of() : assetNames;
        int applied = 0;
        for (ShotEnrich item : enriched.getShots()) {
            if (item == null || item.getChapterIndex() == null || item.getIndex() == null) {
                continue;
            }
            ShotDraftList.ShotDraft shot = byKey.get(item.getChapterIndex() + "\0" + item.getIndex());
            if (shot == null || !acceptable(shot, item, knownNames)) {
                continue;
            }
            shot.setNarrativeGoal(item.getNarrativeGoal().strip());
            shot.setScene(item.getScene().strip());
            applied += 2;
        }
        return applied;
    }

    private static boolean acceptable(ShotDraftList.ShotDraft shot, ShotEnrich item, Set<String> knownNames) {
        String narrative = item.getNarrativeGoal();
        String scene = item.getScene();
        if (!StringUtils.hasText(narrative) || !StringUtils.hasText(scene)) {
            return false;
        }
        narrative = narrative.strip();
        scene = scene.strip();
        if (narrative.length() > MAX_NARRATIVE_GOAL_LENGTH || scene.length() > MAX_SCENE_LENGTH) {
            return false;
        }
        String combined = narrative + '\n' + scene;
        // 原稿正文点过名的角色必须存活：编译器靠名字出现处挂 <Picture N> 锚点，丢了锚点参考图就白绑。
        for (String name : namesIn(knownNames, shot.getNarrativeGoal())) {
            if (!combined.contains(name)) {
                return false;
            }
        }
        // 本镜任何原始字段都没出现过的资产名不得混入（防止把别场角色写进本镜）。
        Set<String> allowed = originalFieldNames(shot, knownNames);
        for (String name : knownNames) {
            if (!allowed.contains(name) && combined.contains(name)) {
                return false;
            }
        }
        // 台词独立编译成 Dialogue 与字幕，混进画面描述会双重口播。
        for (String line : DramaStoryboardCompiler.captionLines(shot.getDialogue())) {
            if (line != null && line.length() >= 4 && combined.contains(line)) {
                return false;
            }
        }
        // 状态描述与动作不能互斥：scene 里出现「线缆/插头/接线」之类暗示有线连接的道具状态描述，
        // 同时 narrative_goal 又写「插入/接上」动作，模型会把 U 盘画成有线连接设备再被「插入」吞掉。
        // 仅当两侧同时命中才拒绝——单独写「线缆盘绕」（无插入动作）或单独写「插入」（无连接词）合法。
        if (CONNECTION_STATE_TOKENS.stream().anyMatch(scene::contains)
                && INSERT_ACTION_TOKENS.stream().anyMatch(narrative::contains)) {
            return false;
        }
        return true;
    }

    /** 暗示道具「有线连接」的状态描述词，用于 scene 字段。 */
    private static final List<String> CONNECTION_STATE_TOKENS = List.of(
            "线缆", "接线", "电绕", "插头", "连线", "插线", "连接着", "拖线"
    );

    /** 道具「插入/接上」动作动词，用于 narrative_goal 字段。 */
    private static final List<String> INSERT_ACTION_TOKENS = List.of(
            "插入", "接上", "插进", "插进主机", "插进接口", "插进电脑", "接入"
    );

    private static Set<String> namesIn(Set<String> names, String text) {
        Set<String> found = new LinkedHashSet<>();
        if (text == null) {
            return found;
        }
        for (String name : names) {
            if (StringUtils.hasText(name) && text.contains(name)) {
                found.add(name);
            }
        }
        return found;
    }

    /** 本镜原始字段里出现过的资产名集合：扩写只许在这个范围内用名字。 */
    private static Set<String> originalFieldNames(ShotDraftList.ShotDraft shot, Set<String> names) {
        List<String> fields = new ArrayList<>(List.of(
                nullToEmpty(shot.getTitle()),
                nullToEmpty(shot.getNarrativeGoal()),
                nullToEmpty(shot.getScene()),
                nullToEmpty(shot.getDialogue()),
                nullToEmpty(shot.getContinuityIn()),
                nullToEmpty(shot.getContinuityOut()),
                nullToEmpty(shot.getLocationName())));
        fields.addAll(shot.getCharacterNames() == null ? List.of() : shot.getCharacterNames());
        fields.addAll(shot.getPropNames() == null ? List.of() : shot.getPropNames());
        return namesIn(names, String.join("\n", fields));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
