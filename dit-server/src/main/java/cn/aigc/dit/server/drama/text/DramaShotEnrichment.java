package cn.aigc.dit.server.drama.text;

import cn.aigc.dit.server.drama.DramaShotBeats;
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
 * 允许改写 scene / camera / narrative_goal / ambiance；任何破坏人物锚点、引入本镜之外的名字、
 * 塞进台词、写入编辑祈使或长度失控的条目整镜回退原稿——扩写永远不让拆镜结果变差。
 */
public final class DramaShotEnrichment {

    /** 硬上限；提示词目标无镜头时 narrative_goal 60~150。有镜头时每个 action 目标 40～120 字，超限整镜回退。 */
    static final int MAX_NARRATIVE_GOAL_LENGTH = 200;
    static final int MAX_BEAT_ACTION_LENGTH = 160;
    static final int MAX_BLOCKING_LENGTH = 120;
    static final int MAX_SCENE_LENGTH = 120;
    static final int MAX_CAMERA_LENGTH = 48;
    static final int MAX_AMBIANCE_LENGTH = 80;

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

        @JsonAlias("camera_motion")
        private String camera;

        @JsonAlias("ambiance_audio")
        private String ambiance;

        private String blocking;

        private List<BeatEnrich> beats;
    }

    @Data
    public static class BeatEnrich {
        private String camera;
        private String action;
        private String continuity;
        private String dialogue;
    }

    /**
     * 把扩写结果合并回草稿，返回成功写入的字段数（scene + narrative_goal 必有，camera / ambiance 有文本才计）。
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
            if (shot == null) {
                continue;
            }
            if (DramaShotBeats.hasBeats(shot.getShotBeats())) {
                if (!acceptableBeats(shot, item, knownNames)) {
                    continue;
                }
                applied += applyBeats(shot, item);
                continue;
            }
            if (!acceptable(shot, item, knownNames)) {
                continue;
            }
            shot.setNarrativeGoal(item.getNarrativeGoal().strip());
            shot.setScene(item.getScene().strip());
            shot.setCamera(blankToNull(item.getCamera()));
            shot.setAmbiance(blankToNull(item.getAmbiance()));
            applied += 2;
            if (StringUtils.hasText(item.getCamera())) {
                applied++;
            }
            if (StringUtils.hasText(item.getAmbiance())) {
                applied++;
            }
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
        String camera = blankToNull(item.getCamera());
        String ambiance = blankToNull(item.getAmbiance());
        if (narrative.length() > MAX_NARRATIVE_GOAL_LENGTH || scene.length() > MAX_SCENE_LENGTH) {
            return false;
        }
        if (camera != null && camera.length() > MAX_CAMERA_LENGTH) {
            return false;
        }
        if (ambiance != null && ambiance.length() > MAX_AMBIANCE_LENGTH) {
            return false;
        }
        String combined = narrative + '\n' + scene + '\n' + nullToEmpty(camera) + '\n' + nullToEmpty(ambiance);
        if (containsEditVerb(combined)) {
            return false;
        }
        // 动作句必须留下原稿点过名的角色：编译器靠 Action 里的名字挂 <Picture N>。
        for (String name : namesIn(knownNames, shot.getNarrativeGoal())) {
            if (!narrative.contains(name)) {
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

    /**
     * 有镜头列表时逐镜头扩写。条数必须对齐；任一镜头人物名丢失、台词混进动作或超长，整镜回退。
     * 不把多个镜头收成一段 narrative_goal。
     */
    private static boolean acceptableBeats(ShotDraftList.ShotDraft shot, ShotEnrich item, Set<String> knownNames) {
        List<DramaShotBeats.Beat> original = shot.getShotBeats().getBeats();
        List<BeatEnrich> enriched = item.getBeats();
        if (enriched == null || enriched.size() != original.size()) {
            return false;
        }
        String scene = item.getScene();
        if (!StringUtils.hasText(scene)) {
            return false;
        }
        scene = scene.strip();
        String blocking = blankToNull(item.getBlocking());
        String ambiance = blankToNull(item.getAmbiance());
        if (scene.length() > MAX_SCENE_LENGTH) {
            return false;
        }
        if (blocking != null && blocking.length() > MAX_BLOCKING_LENGTH) {
            return false;
        }
        if (ambiance != null && ambiance.length() > MAX_AMBIANCE_LENGTH) {
            return false;
        }
        StringBuilder combined = new StringBuilder();
        combined.append(scene).append('\n').append(nullToEmpty(blocking)).append('\n').append(nullToEmpty(ambiance));
        for (int i = 0; i < original.size(); i++) {
            BeatEnrich beat = enriched.get(i);
            DramaShotBeats.Beat source = original.get(i);
            if (beat == null || source == null || !StringUtils.hasText(beat.getAction())) {
                return false;
            }
            String action = beat.getAction().strip();
            String camera = blankToNull(beat.getCamera());
            String continuity = blankToNull(beat.getContinuity());
            if (action.length() > MAX_BEAT_ACTION_LENGTH) {
                return false;
            }
            if (camera != null && camera.length() > MAX_CAMERA_LENGTH) {
                return false;
            }
            if (continuity != null && continuity.length() > MAX_BEAT_ACTION_LENGTH) {
                return false;
            }
            for (String name : namesIn(knownNames, source.getAction())) {
                if (!action.contains(name)) {
                    return false;
                }
            }
            combined.append('\n').append(action).append('\n')
                    .append(nullToEmpty(camera)).append('\n')
                    .append(nullToEmpty(continuity));
        }
        String blob = combined.toString();
        if (containsEditVerb(blob)) {
            return false;
        }
        Set<String> allowed = originalFieldNames(shot, knownNames);
        for (String name : knownNames) {
            if (!allowed.contains(name) && blob.contains(name)) {
                return false;
            }
        }
        for (String line : dialogueLines(shot)) {
            if (line != null && line.length() >= 4 && blob.contains(line)) {
                return false;
            }
        }
        if (CONNECTION_STATE_TOKENS.stream().anyMatch(scene::contains)) {
            for (BeatEnrich beat : enriched) {
                if (beat != null && StringUtils.hasText(beat.getAction())
                        && INSERT_ACTION_TOKENS.stream().anyMatch(beat.getAction()::contains)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static int applyBeats(ShotDraftList.ShotDraft shot, ShotEnrich item) {
        DramaShotBeats beats = shot.getShotBeats();
        String blocking = blankToNull(item.getBlocking());
        if (blocking != null) {
            beats.setBlocking(blocking);
        }
        List<DramaShotBeats.Beat> original = beats.getBeats();
        int applied = 1;
        for (int i = 0; i < original.size(); i++) {
            DramaShotBeats.Beat beat = original.get(i);
            BeatEnrich next = item.getBeats().get(i);
            beat.setAction(next.getAction().strip());
            beat.setCamera(blankToNull(next.getCamera()));
            String continuity = blankToNull(next.getContinuity());
            if (continuity != null) {
                beat.setContinuity(continuity);
            }
            applied++;
            if (StringUtils.hasText(next.getCamera())) {
                applied++;
            }
        }
        shot.setScene(item.getScene().strip());
        shot.setAmbiance(blankToNull(item.getAmbiance()));
        if (StringUtils.hasText(item.getBeats().get(0).getCamera())) {
            shot.setCamera(item.getBeats().get(0).getCamera().strip());
        }
        if (StringUtils.hasText(item.getAmbiance())) {
            applied++;
        }
        DramaShotBeats.syncDraftDialogue(shot);
        return applied;
    }

    private static List<String> dialogueLines(ShotDraftList.ShotDraft shot) {
        List<String> lines = new ArrayList<>(DramaStoryboardCompiler.captionLines(shot.getDialogue()));
        if (shot.getShotBeats() != null && shot.getShotBeats().getBeats() != null) {
            for (DramaShotBeats.Beat beat : shot.getShotBeats().getBeats()) {
                if (beat != null) {
                    lines.addAll(DramaStoryboardCompiler.captionLines(beat.getDialogue()));
                }
            }
        }
        return lines;
    }

    /** 暗示道具「有线连接」的状态描述词，用于 scene 字段。 */
    private static final List<String> CONNECTION_STATE_TOKENS = List.of(
            "线缆", "接线", "电绕", "插头", "连线", "插线", "连接着", "拖线"
    );

    /** 道具「插入/接上」动作动词，用于 narrative_goal 字段。 */
    private static final List<String> INSERT_ACTION_TOKENS = List.of(
            "插入", "接上", "插进", "插进主机", "插进接口", "插进电脑", "接入"
    );

    /**
     * 视频模型会把这些祈使当成「编辑 / 延长」任务。只拦不会出现在正常动作里的写法，
     * 「删除档案」这类剧情动词留给提示词约束，不在这里误杀。
     */
    private static final List<String> EDIT_VERBS = List.of(
            "改成", "替换", "去掉", "续写", "修改成", "删掉"
    );

    private static boolean containsEditVerb(String text) {
        for (String verb : EDIT_VERBS) {
            if (text.contains(verb)) {
                return true;
            }
        }
        return false;
    }

    private static String blankToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.strip();
    }

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
        fields.addAll(DramaShotBeats.mentionTexts(shot.getShotBeats()));
        return namesIn(names, String.join("\n", fields));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
