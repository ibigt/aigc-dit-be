package cn.aigc.dit.server.drama;

import cn.aigc.dit.server.drama.text.ScriptDoc;
import cn.aigc.dit.server.drama.text.ShotDraftList;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 乙未合入时的规则分镜草稿。第一场覆盖开场日常 / 局长闯入 / 掏怀表，时长仅 5–15 秒。
 */
public final class DramaShotDrafts {

    private DramaShotDrafts() {
    }

    public static ShotDraftList fromScript(ScriptDoc doc, List<String> assetNames) {
        return fromScript(doc, assetNames, DramaContract.DEFAULT_DURATION_SEC);
    }

    public static ShotDraftList fromScript(ScriptDoc doc, List<String> assetNames, int durationSec) {
        ShotDraftList list = new ShotDraftList();
        if (doc == null || doc.getScenes() == null || doc.getScenes().isEmpty()) {
            return list;
        }
        int duration = DramaContract.DURATIONS.contains(durationSec)
                ? durationSec
                : DramaContract.DEFAULT_DURATION_SEC;
        Set<String> allowed = normalizeNames(assetNames);
        int fallbackIndex = 0;
        for (ScriptDoc.Scene scene : doc.getScenes()) {
            if (scene == null) {
                continue;
            }
            fallbackIndex++;
            int chapterIndex = scene.getIndex() == null ? fallbackIndex : scene.getIndex();
            ShotDraftList.ChapterDraft chapter = new ShotDraftList.ChapterDraft();
            chapter.setIndex(chapterIndex);
            chapter.setSceneIndex(chapterIndex);
            chapter.setName(chapterName(scene, chapterIndex));
            list.getChapters().add(chapter);
            list.getShots().addAll(shotsForScene(scene, chapterIndex, allowed, duration));
        }
        return list;
    }

    static List<ShotDraftList.ShotDraft> shotsForScene(
            ScriptDoc.Scene scene, int chapterIndex, Set<String> allowed, int durationSec) {
        List<String> characters = pickNames(scene.getCharacters(), allowed);
        List<String> props = pickNames(scene.getProps(), allowed);
        String location = pickLocation(scene.getLocation(), allowed);
        List<String> beats = splitBeats(scene.getAction());
        List<ScriptDoc.Dialogue> lines = scene.getDialogue() == null ? List.of() : scene.getDialogue();
        boolean first = chapterIndex == 1;
        int min = first ? DramaContract.FIRST_SCENE_MIN_SHOTS : DramaContract.MIN_SHOTS_PER_CHAPTER;
        int max = first ? DramaContract.FIRST_SCENE_MAX_SHOTS : DramaContract.MAX_SHOTS_PER_CHAPTER;

        List<ShotSpec> specs = new ArrayList<>();
        specs.add(new ShotSpec(
                first ? "侦探社开场" : headingOr("开场", scene),
                "交代空间与人物关系",
                sceneLine(scene, beats, 0),
                firstLine(lines),
                "开场",
                first && hasName(characters, "局长") ? "局长将至" : "环境立住",
                characters,
                props,
                location));

        if (hasName(characters, "夏安") || containsAny(scene.getAction(), "白板", "照片", "红线")) {
            specs.add(new ShotSpec(
                    "夏安整理线索",
                    "交代夏安在白板前的工作",
                    sceneLine(scene, beats, 1),
                    lineOf(lines, "夏安"),
                    specs.get(specs.size() - 1).continuityOut(),
                    "白板线索立住",
                    characters,
                    props,
                    location));
        }

        if (hasName(characters, "局长") || containsAny(scene.getAction(), "闯入", "摔", "档案")) {
            specs.add(new ShotSpec(
                    "局长闯入",
                    "局长闯入打破日常",
                    sceneLine(scene, beats, Math.min(2, Math.max(0, beats.size() - 1))),
                    lineOf(lines, "局长"),
                    specs.get(specs.size() - 1).continuityOut(),
                    hasName(props, "古铜怀表") || containsAny(scene.getAction(), "怀表")
                            ? "洛白被叫醒" : "冲突立住",
                    characters,
                    props,
                    location));
        } else if (!lines.isEmpty()) {
            ScriptDoc.Dialogue line = lines.get(0);
            specs.add(new ShotSpec(
                    speakerTitle(line, "对白"),
                    "推进对白",
                    sceneLine(scene, beats, 1),
                    formatLine(line),
                    specs.get(specs.size() - 1).continuityOut(),
                    "对白落点",
                    characters,
                    props,
                    location));
        }

        if (hasName(props, "古铜怀表") || containsAny(scene.getAction(), "怀表", "掏表")) {
            specs.add(new ShotSpec(
                    "掏出古铜怀表",
                    "关键道具出场",
                    containsAny(scene.getAction(), "怀表") ? clip(scene.getAction(), 80) : "洛白掏出古铜怀表。",
                    lineOf(lines, "洛白"),
                    specs.get(specs.size() - 1).continuityOut(),
                    "怀表特写",
                    characters,
                    List.of("古铜怀表"),
                    location));
        }

        int lineCursor = 0;
        while (specs.size() < min && lineCursor < lines.size()) {
            ScriptDoc.Dialogue line = lines.get(lineCursor++);
            if (alreadyHasDialogue(specs, formatLine(line))) {
                continue;
            }
            specs.add(new ShotSpec(
                    speakerTitle(line, "对白"),
                    "推进对白",
                    sceneLine(scene, beats, Math.min(lineCursor, Math.max(0, beats.size() - 1))),
                    formatLine(line),
                    specs.get(specs.size() - 1).continuityOut(),
                    "对白落点",
                    characters,
                    props,
                    location));
        }

        int beatCursor = 1;
        while (specs.size() < min && beatCursor < beats.size()) {
            String beat = beats.get(beatCursor++);
            specs.add(new ShotSpec(
                    "动作",
                    beat,
                    beat,
                    null,
                    specs.get(specs.size() - 1).continuityOut(),
                    "动作落点",
                    characters,
                    props,
                    location));
        }

        while (specs.size() < min) {
            specs.add(new ShotSpec(
                    first ? "日常收束" : "场次收束",
                    "收住本场空间与人物",
                    sceneLine(scene, beats, 0),
                    null,
                    specs.get(specs.size() - 1).continuityOut(),
                    "切下一场",
                    characters,
                    props,
                    location));
        }

        if (specs.size() > max) {
            specs = new ArrayList<>(specs.subList(0, max));
        }

        List<ShotDraftList.ShotDraft> shots = new ArrayList<>();
        for (int i = 0; i < specs.size(); i++) {
            ShotSpec spec = specs.get(i);
            ShotDraftList.ShotDraft draft = new ShotDraftList.ShotDraft();
            draft.setChapterIndex(chapterIndex);
            draft.setIndex(i + 1);
            draft.setTitle(spec.title());
            draft.setDurationSec(durationSec);
            draft.setNarrativeGoal(spec.narrativeGoal());
            draft.setScene(spec.scene());
            draft.setDialogue(spec.dialogue());
            draft.setContinuityIn(spec.continuityIn());
            draft.setContinuityOut(spec.continuityOut());
            draft.setCharacterNames(new ArrayList<>(spec.characters()));
            draft.setPropNames(new ArrayList<>(spec.props()));
            draft.setLocationName(spec.location());
            draft.setCastCount(spec.characters().size());
            shots.add(draft);
        }
        if (shots.size() >= 2) {
            shots.get(shots.size() - 1).setContinuityOut("切下一场");
        }
        return shots;
    }

    static int normalizeDuration(Integer value) {
        if (value != null && DramaContract.DURATIONS.contains(value)) {
            return value;
        }
        return DramaContract.DEFAULT_DURATION_SEC;
    }

    static String chapterName(ScriptDoc.Scene scene, int index) {
        if (scene != null && StringUtils.hasText(scene.getHeading())) {
            return scene.getHeading().trim();
        }
        return "第" + index + "场";
    }

    static String chapterBody(ScriptDoc.Scene scene) {
        if (scene == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        if (StringUtils.hasText(scene.getHeading())) {
            parts.add(scene.getHeading().trim());
        }
        if (StringUtils.hasText(scene.getAction())) {
            parts.add(scene.getAction().trim());
        }
        if (StringUtils.hasText(scene.getSound())) {
            parts.add("声音：" + scene.getSound().trim());
        }
        return parts.isEmpty() ? null : String.join("\n", parts);
    }

    private static List<String> pickNames(List<String> names, Set<String> allowed) {
        List<String> source = names == null ? List.of() : names.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
        if (allowed == null || allowed.isEmpty()) {
            return new ArrayList<>(source);
        }
        List<String> matched = new ArrayList<>();
        for (String name : source) {
            String hit = matchAllowed(name, allowed);
            if (hit != null && !matched.contains(hit)) {
                matched.add(hit);
            }
        }
        return matched.isEmpty() ? new ArrayList<>(source) : matched;
    }

    private static String pickLocation(String location, Set<String> allowed) {
        if (!StringUtils.hasText(location)) {
            return null;
        }
        String name = location.trim();
        if (allowed == null || allowed.isEmpty()) {
            return name;
        }
        String hit = matchAllowed(name, allowed);
        return hit != null ? hit : name;
    }

    private static String matchAllowed(String name, Set<String> allowed) {
        if (allowed.contains(name)) {
            return name;
        }
        for (String item : allowed) {
            if (item.equalsIgnoreCase(name) || item.contains(name) || name.contains(item)) {
                return item;
            }
        }
        return null;
    }

    private static Set<String> normalizeNames(List<String> names) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (names == null) {
            return set;
        }
        for (String name : names) {
            if (StringUtils.hasText(name)) {
                set.add(name.trim());
            }
        }
        return set;
    }

    private static List<String> splitBeats(String action) {
        if (!StringUtils.hasText(action)) {
            return List.of();
        }
        String[] parts = action.split("[。！？!?\\n]+");
        List<String> beats = new ArrayList<>();
        for (String part : parts) {
            if (StringUtils.hasText(part)) {
                beats.add(part.trim() + "。");
            }
        }
        return beats;
    }

    private static String sceneLine(ScriptDoc.Scene scene, List<String> beats, int beatIndex) {
        if (beats != null && beatIndex >= 0 && beatIndex < beats.size()) {
            return headingOr("", scene) + (StringUtils.hasText(headingOr("", scene)) ? "，" : "")
                    + beats.get(beatIndex);
        }
        if (StringUtils.hasText(scene.getHeading())) {
            return scene.getHeading().trim();
        }
        if (StringUtils.hasText(scene.getLocation())) {
            return scene.getLocation().trim();
        }
        return "场景";
    }

    private static String headingOr(String fallback, ScriptDoc.Scene scene) {
        return scene != null && StringUtils.hasText(scene.getHeading()) ? scene.getHeading().trim() : fallback;
    }

    private static String firstLine(List<ScriptDoc.Dialogue> lines) {
        return lines == null || lines.isEmpty() ? null : formatLine(lines.get(0));
    }

    private static String lineOf(List<ScriptDoc.Dialogue> lines, String speaker) {
        if (lines == null) {
            return null;
        }
        for (ScriptDoc.Dialogue line : lines) {
            if (line != null && speaker.equals(line.getSpeaker())) {
                return formatLine(line);
            }
        }
        return null;
    }

    private static String formatLine(ScriptDoc.Dialogue line) {
        if (line == null) {
            return null;
        }
        String speaker = line.getSpeaker() == null ? "" : line.getSpeaker().trim();
        String text = line.getLine() == null ? "" : line.getLine().trim();
        if (!StringUtils.hasText(speaker) && !StringUtils.hasText(text)) {
            return null;
        }
        if (!StringUtils.hasText(speaker)) {
            return text;
        }
        return speaker + "：" + text;
    }

    private static String speakerTitle(ScriptDoc.Dialogue line, String fallback) {
        if (line != null && StringUtils.hasText(line.getSpeaker())) {
            return line.getSpeaker().trim() + "对白";
        }
        return fallback;
    }

    private static boolean alreadyHasDialogue(List<ShotSpec> specs, String dialogue) {
        if (!StringUtils.hasText(dialogue)) {
            return false;
        }
        for (ShotSpec spec : specs) {
            if (dialogue.equals(spec.dialogue())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasName(List<String> names, String wanted) {
        if (names == null || !StringUtils.hasText(wanted)) {
            return false;
        }
        for (String name : names) {
            if (wanted.equals(name) || (name != null && name.contains(wanted))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String text, String... needles) {
        if (!StringUtils.hasText(text) || needles == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (StringUtils.hasText(needle) && lower.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String clip(String value, int max) {
        String text = value.trim().replaceAll("\\s+", " ");
        return text.length() <= max ? text : text.substring(0, max);
    }

    private record ShotSpec(
            String title,
            String narrativeGoal,
            String scene,
            String dialogue,
            String continuityIn,
            String continuityOut,
            List<String> characters,
            List<String> props,
            String location
    ) {
    }
}
