package cn.aigc.dit.server.drama.text;

import cn.aigc.dit.server.drama.DramaDialogueSpeakers;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 无文本模型时的规则分场。只认三种行：场标记行、{@code 名字：台词} 行、其余动作行。
 * <p>
 * 可选元信息行（{@code 人物：} / {@code 道具：} / {@code 地点：} / {@code 时间：} / {@code 声音：}）
 * 补足规则无法从散文里可靠识别的字段；缺失时留空，由人工或文本模型补。
 * 场次号按出现位置定序，作者写的「第五场」只保留在 heading 文本里。
 */
public final class ScriptParser {

    private static final Pattern SCENE_START = Pattern.compile(
            "^\\s*(?:第\\s*[0-9一二三四五六七八九十]{1,4}\\s*场|场\\s*[0-9]{1,3})\\s*(.*)$");
    /**
     * 方括号场标记：【场景 1：紫禁城，冷宫，深夜】【第1场 幻梦侦探社】【场 2 …】。
     * 只给 v2 的 {@link #detectSceneMarkers} 用；现有 {@link #parse} 仍只认 {@link #SCENE_START}。
     */
    private static final Pattern SCENE_START_BRACKET = Pattern.compile(
            "^\\s*【\\s*(?:场景|场|第\\s*[0-9一二三四五六七八九十]{1,4}\\s*场)"
                    + "\\s*[0-9一二三四五六七八九十]{0,4}\\s*[：:、.．]?\\s*(.*?)\\s*】\\s*$");
    private static final Pattern DIALOGUE = Pattern.compile(
            "^\\s*([^：:\\s()（）「」『』\"]{1,12})\\s*[：:]\\s*(.+)$");
    private static final Pattern SEPARATOR = Pattern.compile("^[-=_—─\\s]*$");

    private static final Set<String> TIME_WORDS = Set.of(
            "日", "白天", "清晨", "晨", "上午", "中午", "下午", "傍晚", "黄昏", "夜", "晚上", "深夜", "凌晨");
    private static final List<String> META_KEYS = List.of(
            "人物", "角色", "道具", "地点", "场景", "时间", "声音", "音效");

    private ScriptParser() {
    }

    /**
     * 按场标记切成原文片段；无场标记时返回整本单段。
     * 首段含标题/梗概等场前前缀，便于分片 LLM 仍能看到剧本元信息。
     */
    public static List<String> splitRawSegments(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of(raw == null ? "" : raw);
        }
        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = new ArrayList<>();
        for (String line : normalized.split("\n", -1)) {
            lines.add(line);
        }
        int firstScene = 0;
        while (firstScene < lines.size() && !SCENE_START.matcher(lines.get(firstScene).strip()).matches()) {
            firstScene++;
        }
        if (firstScene == lines.size()) {
            return List.of(normalized);
        }
        List<String> segments = new ArrayList<>();
        String header = firstScene == 0 ? "" : String.join("\n", lines.subList(0, firstScene)).strip();
        int start = firstScene;
        for (int i = firstScene + 1; i < lines.size(); i++) {
            if (SCENE_START.matcher(lines.get(i).strip()).matches()) {
                segments.add(joinSegment(header, lines, start, i));
                header = "";
                start = i;
            }
        }
        segments.add(joinSegment(header, lines, start, lines.size()));
        return segments;
    }

    private static String joinSegment(String header, List<String> lines, int from, int to) {
        String body = String.join("\n", lines.subList(from, to)).strip();
        if (!StringUtils.hasText(header)) {
            return body;
        }
        if (!StringUtils.hasText(body)) {
            return header;
        }
        return header + "\n\n" + body;
    }

    /**
     * 原文里显式写出的场次锚点。
     *
     * @param index   按出现顺序的场次序号（从 1 起）
     * @param heading 场次标题原文（可能是空串）
     */
    public record SceneMarker(int index, String heading) {
    }

    /**
     * 识别原文里显式写出的场次标记，按出现顺序返回锚点。
     *
     * <p>v2 用它按原文场次切场。现有规则解析入口不调用本方法。
     */
    public static List<SceneMarker> detectSceneMarkers(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        List<SceneMarker> markers = new ArrayList<>();
        for (String line : raw.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String heading = sceneHeading(line);
            if (heading != null) {
                markers.add(new SceneMarker(markers.size() + 1, heading));
            }
        }
        return markers;
    }

    /** 场标记行的标题；不是场标记行时返回 null（标题本身可以是空串）。 */
    private static String sceneHeading(String line) {
        if (line == null) {
            return null;
        }
        String text = line.strip();
        Matcher plain = SCENE_START.matcher(text);
        if (plain.matches()) {
            return plain.group(1).strip();
        }
        Matcher bracket = SCENE_START_BRACKET.matcher(text);
        if (bracket.matches()) {
            return bracket.group(1).strip();
        }
        return null;
    }

    /**
     * 场次标题里指明的那处地点。
     *
     * <p>【场景 1：紫禁城，冷宫，深夜】→ 冷宫；【第一场 幻梦侦探社 日 内】→ 幻梦侦探社；
     * 判断不出来时返回空串。只给 v2 场次校验用，现有 {@link #parse} 不调用。
     */
    public static String headingPlace(String heading) {
        if (!StringUtils.hasText(heading)) {
            return "";
        }
        List<String> parts = splitHeadingParts(heading);
        if (parts.size() > 1) {
            List<String> places = new ArrayList<>();
            for (String part : parts) {
                if (!containsTimeWord(part)) {
                    places.add(part);
                }
            }
            return places.isEmpty() ? "" : places.get(places.size() - 1).strip();
        }
        List<String> rest = new ArrayList<>();
        for (String token : parts.get(0).split("[\\s/、·／]+")) {
            String word = token.strip();
            if (!StringUtils.hasText(word) || isInteriorWord(word) || TIME_WORDS.contains(word)) {
                continue;
            }
            rest.add(word);
        }
        return rest.isEmpty() ? "" : String.join(" ", rest);
    }

    private static boolean isInteriorWord(String word) {
        return "内".equals(word) || "内景".equals(word) || "室内".equals(word)
                || "外".equals(word) || "外景".equals(word) || "室外".equals(word);
    }

    private static List<String> splitHeadingParts(String heading) {
        List<String> parts = new ArrayList<>();
        for (String part : heading.split("[，,、]")) {
            String text = part.strip();
            if (StringUtils.hasText(text)) {
                parts.add(text);
            }
        }
        if (parts.isEmpty()) {
            parts.add(heading.strip());
        }
        return parts;
    }

    private static boolean containsTimeWord(String part) {
        String text = part.strip();
        for (String word : TIME_WORDS) {
            if (text.equals(word) || text.startsWith(word + "（") || text.startsWith(word + "(")) {
                return true;
            }
        }
        for (String token : text.split("[\\s/·／]+")) {
            if (TIME_WORDS.contains(token.strip())) {
                return true;
            }
        }
        return false;
    }

    public static ScriptDoc parse(String raw) {
        ScriptDoc doc = new ScriptDoc();
        if (!StringUtils.hasText(raw)) {
            return doc;
        }
        List<String> lines = new ArrayList<>();
        for (String line : raw.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            lines.add(line.strip());
        }

        int firstScene = 0;
        while (firstScene < lines.size() && !SCENE_START.matcher(lines.get(firstScene)).matches()) {
            firstScene++;
        }
        if (firstScene == lines.size()) {
            // 全文没有场标记：整段当一场，避免把梗概解析成空。
            firstScene = 0;
            doc.getScenes().add(buildScene(1, null, lines));
            return doc;
        }
        applyHeader(doc, lines.subList(0, firstScene));

        List<String> buffer = new ArrayList<>();
        String heading = null;
        for (int i = firstScene; i < lines.size(); i++) {
            Matcher start = SCENE_START.matcher(lines.get(i));
            if (start.matches()) {
                if (heading != null) {
                    doc.getScenes().add(buildScene(doc.getScenes().size() + 1, heading, buffer));
                    buffer = new ArrayList<>();
                }
                heading = start.group(1).strip();
            } else if (heading != null) {
                buffer.add(lines.get(i));
            }
        }
        doc.getScenes().add(buildScene(doc.getScenes().size() + 1, heading, buffer));
        return doc;
    }

    private static void applyHeader(ScriptDoc doc, List<String> leading) {
        List<String> texts = nonBlank(leading);
        if (texts.isEmpty()) {
            return;
        }
        doc.setTitle(texts.get(0));
        if (texts.size() > 1) {
            doc.setLogline(String.join(" ", texts.subList(1, texts.size())));
        }
    }

    private static ScriptDoc.Scene buildScene(int index, String heading, List<String> lines) {
        ScriptDoc.Scene scene = new ScriptDoc.Scene();
        scene.setIndex(index);
        scene.setHeading(StringUtils.hasText(heading) ? heading.strip() : null);
        applyHeading(scene);

        List<String> actionLines = new ArrayList<>();
        List<String> metaCharacters = new ArrayList<>();
        List<String> metaProps = new ArrayList<>();
        for (String line : lines) {
            if (!StringUtils.hasText(line) || SEPARATOR.matcher(line).matches()) {
                continue;
            }
            Matcher dialogue = DIALOGUE.matcher(line);
            if (dialogue.matches()) {
                String key = dialogue.group(1);
                if (META_KEYS.contains(key)) {
                    applyMeta(scene, metaCharacters, metaProps, key, dialogue.group(2));
                } else {
                    ScriptDoc.Dialogue bit = new ScriptDoc.Dialogue();
                    bit.setSpeaker(DramaDialogueSpeakers.normalizeSpeaker(key.strip()));
                    bit.setLine(stripActionMarker(dialogue.group(2)));
                    bit.setEmotion(null);
                    scene.getDialogue().add(bit);
                }
                continue;
            }
            actionLines.add(stripActionMarker(line));
        }
        if (!actionLines.isEmpty()) {
            scene.setAction(joinActions(actionLines));
        }
        scene.getProps().addAll(metaProps);

        Set<String> characters = new LinkedHashSet<>();
        for (String name : metaCharacters) {
            if (DramaDialogueSpeakers.isRealCharacterName(name)) {
                characters.add(name.strip());
            }
        }
        for (ScriptDoc.Dialogue bit : scene.getDialogue()) {
            if (bit != null && DramaDialogueSpeakers.isRealCharacterName(bit.getSpeaker())) {
                characters.add(bit.getSpeaker().strip());
            }
        }
        scene.getCharacters().addAll(characters);
        scene.setSlug("scene-" + index);
        return scene;
    }

    private static void applyHeading(ScriptDoc.Scene scene) {
        String heading = scene.getHeading();
        if (!StringUtils.hasText(heading)) {
            return;
        }
        List<String> rest = new ArrayList<>();
        for (String token : heading.split("[\\s/、·／]+")) {
            String word = token.strip();
            if (!StringUtils.hasText(word)) {
                continue;
            }
            if ("内".equals(word) || "内景".equals(word) || "室内".equals(word)) {
                scene.setInterior(true);
            } else if ("外".equals(word) || "外景".equals(word) || "室外".equals(word)) {
                scene.setInterior(false);
            } else if (TIME_WORDS.contains(word)) {
                scene.setTime(word);
            } else {
                rest.add(word);
            }
        }
        if (!rest.isEmpty()) {
            scene.setLocation(String.join(" ", rest));
        }
    }

    private static void applyMeta(ScriptDoc.Scene scene, List<String> characters, List<String> props,
                                  String key, String value) {
        List<String> parts = splitList(value);
        switch (key) {
            case "人物", "角色" -> characters.addAll(parts);
            case "道具" -> props.addAll(parts);
            case "地点", "场景" -> {
                if (!parts.isEmpty()) {
                    scene.setLocation(parts.get(0));
                }
            }
            case "时间" -> {
                if (!parts.isEmpty()) {
                    scene.setTime(parts.get(0));
                }
            }
            case "声音", "音效" -> scene.setSound(value.strip());
            default -> {
            }
        }
    }

    private static List<String> splitList(String value) {
        List<String> parts = new ArrayList<>();
        for (String part : value.split("[、,，;；/]")) {
            if (StringUtils.hasText(part)) {
                parts.add(part.strip());
            }
        }
        return parts;
    }

    private static String joinActions(List<String> lines) {
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            String clean = line.strip();
            if (clean.isEmpty()) {
                continue;
            }
            text.append(clean);
            if (!clean.matches(".*[。！？…”』」]$" )) {
                text.append("。");
            }
        }
        return text.toString();
    }

    private static String stripActionMarker(String line) {
        return line.strip().replaceAll("^[△▲]+\\s*", "").strip();
    }

    private static List<String> nonBlank(List<String> lines) {
        List<String> texts = new ArrayList<>();
        for (String line : lines) {
            if (StringUtils.hasText(line) && !SEPARATOR.matcher(line).matches()) {
                texts.add(line.strip());
            }
        }
        return texts;
    }
}
