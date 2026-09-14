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

        String furnishings = sceneFurnishings(scene);
        List<ShotSpec> specs = new ArrayList<>();
        String openingGoal = openingAction(beats, scene, characters);
        specs.add(clipSpec(
                first ? "侦探社开场" : headingOr("开场", scene),
                openingGoal,
                furnishings,
                lineMatching(lines, namesIn(characters, openingGoal)),
                hookIn(openingGoal, "画面起幅"),
                hookOut(openingGoal, "画面落幅"),
                characters,
                props,
                location,
                null));

        if (hasName(characters, "夏安") || containsAny(scene.getAction(), "白板", "照片", "红线")) {
            String goal = beatAction(beats, 1, "中景", "白板", "照片", "红线", "夏安");
            String line = lineOf(lines, "夏安");
            specs.add(clipSpec(
                    "夏安整理线索",
                    goal,
                    furnishings,
                    line,
                    specs.get(specs.size() - 1).continuityOut(),
                    hookOut(goal, specs.get(specs.size() - 1).continuityOut()),
                    characters,
                    props,
                    location,
                    "夏安"));
        }

        if (hasName(characters, "局长") || containsAny(scene.getAction(), "闯入", "摔", "档案")) {
            String matched = matchBeat(beats, "闯入", "摔", "档案");
            ScriptDoc.Dialogue directorLine = dialogueOf(lines, "局长");
            String goal = StringUtils.hasText(matched)
                    ? visibleAction(matched, "中景")
                    : speakingAction(directorLine, List.of(), 0);
            specs.add(clipSpec(
                    "局长闯入",
                    goal,
                    furnishings,
                    formatLine(directorLine),
                    specs.get(specs.size() - 1).continuityOut(),
                    hookOut(goal, specs.get(specs.size() - 1).continuityOut()),
                    characters,
                    props,
                    location,
                    "局长"));
        } else if (!lines.isEmpty()) {
            ScriptDoc.Dialogue line = lines.get(0);
            String goal = speakingAction(line, beats, 1);
            specs.add(clipSpec(
                    speakerTitle(line, "对白"),
                    goal,
                    furnishings,
                    formatLine(line),
                    specs.get(specs.size() - 1).continuityOut(),
                    hookOut(goal, specs.get(specs.size() - 1).continuityOut()),
                    characters,
                    props,
                    location,
                    line.getSpeaker()));
        }

        if (hasName(props, "古铜怀表") || containsAny(scene.getAction(), "怀表", "掏表")) {
            String goal = watchAction(beats);
            String line = lineOf(lines, "洛白");
            specs.add(clipSpec(
                    "掏出古铜怀表",
                    goal,
                    furnishings,
                    line,
                    specs.get(specs.size() - 1).continuityOut(),
                    hookOut(goal, specs.get(specs.size() - 1).continuityOut()),
                    characters,
                    props,
                    location,
                    "洛白"));
        }

        int lineCursor = 0;
        while (specs.size() < min && lineCursor < lines.size()) {
            ScriptDoc.Dialogue line = lines.get(lineCursor++);
            if (alreadyHasDialogue(specs, formatLine(line))) {
                continue;
            }
            String goal = speakingAction(line, beats, Math.min(lineCursor, Math.max(0, beats.size() - 1)));
            specs.add(clipSpec(
                    speakerTitle(line, "对白"),
                    goal,
                    furnishings,
                    formatLine(line),
                    specs.get(specs.size() - 1).continuityOut(),
                    hookOut(goal, specs.get(specs.size() - 1).continuityOut()),
                    characters,
                    props,
                    location,
                    line.getSpeaker()));
        }

        int beatCursor = 1;
        while (specs.size() < min && beatCursor < beats.size()) {
            String beat = beats.get(beatCursor++);
            String goal = visibleAction(beat, "中景");
            specs.add(clipSpec(
                    "动作",
                    goal,
                    furnishings,
                    null,
                    specs.get(specs.size() - 1).continuityOut(),
                    hookOut(goal, specs.get(specs.size() - 1).continuityOut()),
                    characters,
                    props,
                    location,
                    null));
        }

        while (specs.size() < min) {
            String goal = visibleAction(beatAt(beats, Math.max(0, beats.size() - 1)), "中景");
            specs.add(clipSpec(
                    first ? "日常收束" : "场次收束",
                    goal,
                    furnishings,
                    null,
                    specs.get(specs.size() - 1).continuityOut(),
                    hookOut(goal, specs.get(specs.size() - 1).continuityOut()),
                    characters,
                    props,
                    location,
                    null));
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

    private static String sceneFurnishings(ScriptDoc.Scene scene) {
        if (scene != null && StringUtils.hasText(scene.getHeading())) {
            return scene.getHeading().trim();
        }
        if (scene != null && StringUtils.hasText(scene.getLocation())) {
            return scene.getLocation().trim();
        }
        return "场景";
    }

    private static String openingAction(List<String> beats, ScriptDoc.Scene scene, List<String> characters) {
        if (beats == null || beats.isEmpty()) {
            String fallback = clip(scene == null ? null : scene.getAction(), 80);
            return visibleAction(fallback, sizeFor(namesIn(characters, fallback)));
        }
        List<String> parts = new ArrayList<>();
        for (String beat : beats) {
            if (containsAny(beat, "闯入", "摔", "档案", "怀表", "掏表")) {
                if (parts.isEmpty()) {
                    parts.add(stripEnding(beat));
                }
                break;
            }
            parts.add(stripEnding(beat));
            if (parts.size() >= 3) {
                break;
            }
        }
        if (parts.isEmpty()) {
            parts.add(stripEnding(beats.get(0)));
        }
        String packed = String.join("，", parts);
        return visibleAction(packed, sizeFor(namesIn(characters, packed)));
    }

    private static String beatAction(List<String> beats, int fallbackIndex, String shotSize, String... needles) {
        List<String> parts = new ArrayList<>();
        for (String beat : beats == null ? List.<String>of() : beats) {
            if (containsAny(beat, needles)) {
                parts.add(stripEnding(beat));
                if (parts.size() >= 3) {
                    break;
                }
            } else if (!parts.isEmpty()) {
                break;
            }
        }
        if (parts.isEmpty()) {
            return visibleAction(beatAt(beats, fallbackIndex), shotSize);
        }
        return visibleAction(String.join("，", parts), shotSize);
    }

    private static String watchAction(List<String> beats) {
        String matched = matchBeat(beats, "怀表", "掏表");
        if (!StringUtils.hasText(matched)) {
            matched = "洛白掏出古铜怀表";
        }
        return visibleAction(matched, "特写");
    }

    private static String speakingAction(ScriptDoc.Dialogue line, List<String> beats, int beatIndex) {
        String beat = beatAt(beats, beatIndex);
        if (StringUtils.hasText(beat)) {
            return visibleAction(beat, "中景");
        }
        String speaker = line != null && StringUtils.hasText(line.getSpeaker()) ? line.getSpeaker().trim() : "角色";
        return visibleAction(speaker + "开口说话", "中景");
    }

    private static String visibleAction(String action, String shotSize) {
        String size = StringUtils.hasText(shotSize) ? shotSize.trim() : "中景";
        String text = stripEnding(action);
        if (!StringUtils.hasText(text)) {
            return size + "。";
        }
        if (startsWithShotSize(text)) {
            return ensurePeriod(text);
        }
        return size + "。" + ensurePeriod(text);
    }

    private static String matchBeat(List<String> beats, String... needles) {
        if (beats == null || beats.isEmpty() || needles == null) {
            return "";
        }
        for (String beat : beats) {
            if (containsAny(beat, needles)) {
                return beat;
            }
        }
        return "";
    }

    private static String beatAt(List<String> beats, int index) {
        if (beats == null || beats.isEmpty()) {
            return "";
        }
        int i = Math.min(Math.max(index, 0), beats.size() - 1);
        return beats.get(i);
    }

    private static String stripEnding(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String text = value.trim();
        while (text.endsWith("。") || text.endsWith(".") || text.endsWith("！") || text.endsWith("!")) {
            text = text.substring(0, text.length() - 1).trim();
        }
        return text;
    }

    private static String ensurePeriod(String value) {
        String text = value.trim();
        if (text.endsWith("。") || text.endsWith(".") || text.endsWith("！") || text.endsWith("!")
                || text.endsWith("？") || text.endsWith("?")) {
            return text;
        }
        return text + "。";
    }

    private static boolean startsWithShotSize(String text) {
        return text.startsWith("特写") || text.startsWith("近景") || text.startsWith("中景")
                || text.startsWith("全景") || text.startsWith("远景") || text.startsWith("过肩")
                || text.startsWith("跟镜头") || text.startsWith("中近景");
    }

    private static String sizeFor(List<String> cast) {
        return cast != null && cast.size() >= 2 ? "全景" : "中景";
    }

    private static ShotSpec clipSpec(
            String title,
            String goal,
            String furnishings,
            String dialogue,
            String continuityIn,
            String continuityOut,
            List<String> characters,
            List<String> props,
            String location,
            String ensureCharacter) {
        List<String> cast = namesIn(characters, goal, dialogue);
        if (StringUtils.hasText(ensureCharacter)) {
            cast = ensureName(cast, characters, ensureCharacter);
        }
        List<String> shotProps = namesIn(props, goal, dialogue);
        if (containsAny(goal, "怀表", "掏表")) {
            shotProps = ensureName(shotProps, props, "古铜怀表");
        }
        return new ShotSpec(
                title,
                goal,
                furnishings,
                dialogue,
                continuityIn,
                continuityOut,
                cast,
                shotProps,
                location);
    }

    private static List<String> namesIn(List<String> pool, String... texts) {
        return new ArrayList<>(DramaNameMentions.mentionedInOrder(pool, texts));
    }

    private static List<String> ensureName(List<String> current, List<String> pool, String wanted) {
        List<String> next = current == null ? new ArrayList<>() : new ArrayList<>(current);
        if (!StringUtils.hasText(wanted) || hasName(next, wanted)) {
            return next;
        }
        String hit = matchFromPool(pool, wanted);
        if (hit != null) {
            next.add(hit);
        }
        return next;
    }

    private static String matchFromPool(List<String> pool, String wanted) {
        if (pool == null || !StringUtils.hasText(wanted)) {
            return null;
        }
        for (String name : pool) {
            if (wanted.equals(name) || (name != null && name.contains(wanted))) {
                return name;
            }
        }
        return null;
    }

    private static String hookIn(String action, String fallback) {
        return firstOrLastHook(action, fallback, true);
    }

    private static String hookOut(String action, String fallback) {
        return firstOrLastHook(action, fallback, false);
    }

    private static String firstOrLastHook(String action, String fallback, boolean first) {
        String text = stripShotSizePrefix(stripEnding(action));
        if (!StringUtils.hasText(text)) {
            return StringUtils.hasText(fallback) ? fallback : "画面起幅";
        }
        String[] parts = text.split("[，；;、]+");
        String pick = first ? parts[0].trim() : parts[parts.length - 1].trim();
        if (!StringUtils.hasText(pick)) {
            pick = text;
        }
        return clip(pick, 24);
    }

    private static String stripShotSizePrefix(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String text = value.trim();
        if (!startsWithShotSize(text)) {
            return text;
        }
        int cut = text.indexOf('。');
        int comma = text.indexOf('，');
        if (cut < 0 || (comma >= 0 && comma < cut)) {
            cut = comma;
        }
        if (cut >= 0 && cut + 1 < text.length()) {
            return text.substring(cut + 1).trim();
        }
        return text;
    }

    private static String headingOr(String fallback, ScriptDoc.Scene scene) {
        return scene != null && StringUtils.hasText(scene.getHeading()) ? scene.getHeading().trim() : fallback;
    }

    private static String lineMatching(List<ScriptDoc.Dialogue> lines, List<String> cast) {
        if (lines == null || cast == null || cast.isEmpty()) {
            return null;
        }
        for (ScriptDoc.Dialogue line : lines) {
            if (line != null && hasName(cast, line.getSpeaker())) {
                return formatLine(line);
            }
        }
        return null;
    }

    private static ScriptDoc.Dialogue dialogueOf(List<ScriptDoc.Dialogue> lines, String speaker) {
        if (lines == null || !StringUtils.hasText(speaker)) {
            return null;
        }
        for (ScriptDoc.Dialogue line : lines) {
            if (line != null && speaker.equals(line.getSpeaker())) {
                return line;
            }
        }
        return null;
    }

    private static String lineOf(List<ScriptDoc.Dialogue> lines, String speaker) {
        return formatLine(dialogueOf(lines, speaker));
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
        if (!StringUtils.hasText(value)) {
            return "";
        }
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
