package cn.aigc.dit.server.drama;

import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 分镜视频 prompt 编译器。每镜自包含：Picture 锚点 + 风格前缀 + Active References（完整身份短句）
 * + 电影场面硬约束 + 场景/动作/台词（角色名绑到 Picture）+ Cast + 对白口型。
 * 官方 Seedance 按参考图像素审图，不靠提示词里删「脸/五官」或写「不是真人」绕过。
 */
public final class DramaStoryboardCompiler {

    public static final String CHAR_PICTURE_HINT = "只借%s的身份与当前服装，不继承姿态、背景、构图、机位、光线";
    public static final String PROP_PICTURE_HINT = "只借%s的外观与材质，必须在戏里被使用，不继承姿态、背景、构图、机位、光线";
    public static final String SCENE_PICTURE_HINT = "只借空间陈设，必须有人在此演戏，不继承原图构图、机位、光线";
    public static final String MOVIE_SCENE_MUST =
            "Must: 本镜是电影场面，不是设定图。禁止多视角设定表、纯色背景设定页、无人概念图、静物白底。";
    public static final String DIALOGUE_EXECUTION_TAIL = "只念台词正文，不念角色名和冒号；口型与每个字严格同步，不得增删或改写。";

    private DramaStoryboardCompiler() {
    }

    public record ActiveReference(
            long assetId,
            int identityVersion,
            String name,
            String type,
            String descriptor,
            String identityInvariants
    ) {
    }

    /**
     * 本镜点名但未锁定出图的角色，用外貌短句代替点名，避免模型乱长脸。
     */
    public record NamedAppearance(String name, String identityInvariants) {
    }

    public record ShotContent(
            String scene,
            String narrativeGoal,
            String dialogue,
            String continuityIn,
            String continuityOut,
            int durationSec,
            List<String> characterNames,
            int castCount,
            List<NamedAppearance> unnamedAppearances
    ) {
        public ShotContent(
                String scene,
                String narrativeGoal,
                String dialogue,
                String continuityIn,
                String continuityOut,
                int durationSec,
                List<String> characterNames,
                int castCount) {
            this(scene, narrativeGoal, dialogue, continuityIn, continuityOut, durationSec,
                    characterNames, castCount, List.of());
        }
    }

    public record Result(String prompt, String fingerprint) {
    }

    public static Result compile(String stylePrefix, List<ActiveReference> references, ShotContent shot) {
        List<ActiveReference> refs = references == null ? List.of() : references;
        ShotContent content = shot == null
                ? new ShotContent(null, null, null, null, null, DramaContract.DEFAULT_DURATION_SEC, List.of(), 0)
                : shot;
        String prefix = DramaStyles.withVideoSceneClause(StringUtils.hasText(stylePrefix)
                ? stylePrefix.trim()
                : DramaStyles.require(DramaStyles.ANIME_COMIC).prefix());

        StringBuilder prompt = new StringBuilder();
        if (!refs.isEmpty()) {
            for (int i = 1; i <= refs.size(); i++) {
                if (i > 1) {
                    prompt.append(' ');
                }
                prompt.append("<Picture ").append(i).append('>');
            }
            prompt.append('\n');
        }
        prompt.append(prefix);
        if (!prefix.endsWith("。") && !prefix.endsWith(".")) {
            prompt.append('。');
        }
        prompt.append('\n');

        if (!refs.isEmpty()) {
            prompt.append('\n').append("Active References:\n");
            for (int i = 0; i < refs.size(); i++) {
                ActiveReference ref = refs.get(i);
                int picture = i + 1;
                prompt.append("<Picture ").append(picture).append("> ");
                prompt.append(blank(ref.name()));
                prompt.append('。');
                String identity = fullIdentity(ref);
                if (StringUtils.hasText(identity)) {
                    prompt.append(identity);
                    if (!identity.endsWith("。") && !identity.endsWith(".")) {
                        prompt.append('。');
                    }
                }
                prompt.append(' ');
                prompt.append("<Picture ").append(picture).append("> ");
                prompt.append(pictureHint(ref));
                prompt.append('\n');
            }
        }

        prompt.append('\n').append(MOVIE_SCENE_MUST).append('\n');
        Map<String, Integer> pictures = pictureIndexByName(refs);
        appendLine(prompt, "Scene", bindPictureNames(content.scene(), pictures));
        appendLine(prompt, "Action", bindPictureNames(content.narrativeGoal(), pictures));
        appendLine(prompt, "Dialogue", bindPictureNames(content.dialogue(), pictures));
        appendLine(prompt, "Continuity in", bindPictureNames(content.continuityIn(), pictures));
        appendLine(prompt, "Continuity out", bindPictureNames(content.continuityOut(), pictures));
        prompt.append(castConstraint(content, refs)).append('\n');
        prompt.append("Duration: ").append(content.durationSec()).append(" seconds.");
        String spoken = spokenDialogueConstraint(content.dialogue());
        if (StringUtils.hasText(spoken)) {
            prompt.append('\n').append(spoken);
        }

        return new Result(prompt.toString().trim(), fingerprint(prefix, refs, content));
    }

    public static String fingerprint(String stylePrefix, List<ActiveReference> references, ShotContent shot) {
        StringBuilder raw = new StringBuilder();
        raw.append(blank(stylePrefix)).append('\n');
        if (references != null) {
            for (ActiveReference ref : references) {
                raw.append(ref.assetId()).append(':').append(ref.identityVersion()).append(':')
                        .append(blank(ref.identityInvariants())).append('\n');
            }
        }
        if (shot != null) {
            raw.append(shot.durationSec()).append('\n');
            raw.append(blank(shot.scene())).append('\n');
            raw.append(blank(shot.narrativeGoal())).append('\n');
            raw.append(blank(shot.dialogue())).append('\n');
            raw.append(blank(shot.continuityIn())).append('\n');
            raw.append(blank(shot.continuityOut())).append('\n');
            raw.append(String.join(",", shot.characterNames() == null ? List.of() : shot.characterNames()));
            raw.append('\n').append(shot.castCount());
            if (shot.unnamedAppearances() != null) {
                for (NamedAppearance appearance : shot.unnamedAppearances()) {
                    if (appearance == null) {
                        continue;
                    }
                    raw.append('\n').append(blank(appearance.name())).append(':')
                            .append(blank(appearance.identityInvariants()));
                }
            }
        }
        return sha256(raw.toString());
    }

    static Map<String, Integer> pictureIndexByName(List<ActiveReference> references) {
        Map<String, Integer> pictures = new LinkedHashMap<>();
        if (references == null) {
            return pictures;
        }
        for (int i = 0; i < references.size(); i++) {
            ActiveReference ref = references.get(i);
            if (ref != null && StringUtils.hasText(ref.name())) {
                pictures.putIfAbsent(ref.name().trim(), i + 1);
            }
        }
        return pictures;
    }

    static String bindPictureNames(String text, Map<String, Integer> pictures) {
        if (!StringUtils.hasText(text) || pictures == null || pictures.isEmpty()) {
            return text;
        }
        String result = text;
        List<String> names = new ArrayList<>(pictures.keySet());
        names.sort((left, right) -> Integer.compare(right.length(), left.length()));
        for (String name : names) {
            Integer picture = pictures.get(name);
            if (picture == null || !result.contains(name)) {
                continue;
            }
            String token = name + "（<Picture " + picture + ">）";
            if (result.contains(token)) {
                continue;
            }
            result = result.replace(name, token);
        }
        return result;
    }

    static String fullIdentity(ActiveReference ref) {
        if (ref == null || !StringUtils.hasText(ref.identityInvariants())) {
            return "";
        }
        return ref.identityInvariants().trim();
    }

    static String pictureHint(ActiveReference ref) {
        String name = StringUtils.hasText(ref.name()) ? ref.name().trim() : "该资产";
        if (DramaContract.ASSET_TYPE_SCENE.equals(ref.type())) {
            return SCENE_PICTURE_HINT;
        }
        if (DramaContract.ASSET_TYPE_PROP.equals(ref.type())) {
            return PROP_PICTURE_HINT.formatted(name);
        }
        return CHAR_PICTURE_HINT.formatted(name);
    }

    static String castConstraint(ShotContent shot, List<ActiveReference> references) {
        List<String> names = shot.characterNames() == null ? List.of() : shot.characterNames().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
        int count = shot.castCount() > 0 ? shot.castCount() : names.size();
        if (count <= 0 && names.isEmpty()) {
            return "Cast: 本镜不要增删人物。";
        }

        Set<String> pictured = new LinkedHashSet<>();
        if (references != null) {
            for (ActiveReference ref : references) {
                if (ref != null
                        && DramaContract.ASSET_TYPE_CHARACTER.equals(ref.type())
                        && StringUtils.hasText(ref.name())) {
                    pictured.add(ref.name().trim());
                }
            }
        }

        List<String> picturedNames = names.stream().filter(pictured::contains).toList();
        List<String> unnamedLooks = unnamedLooks(names, pictured, shot.unnamedAppearances());

        StringBuilder line = new StringBuilder();
        line.append("Cast: 本镜恰好 ").append(count).append(" 人，不得增删角色。");
        if (!picturedNames.isEmpty()) {
            line.append("出场：");
            List<String> labeled = new ArrayList<>();
            Map<String, Integer> pictures = pictureIndexByName(references);
            for (String name : picturedNames) {
                Integer picture = pictures.get(name);
                labeled.add(picture == null ? name : name + "=<Picture " + picture + ">");
            }
            line.append(String.join("、", labeled)).append("。");
        }
        if (!unnamedLooks.isEmpty()) {
            line.append("未出图角色按外貌出演：").append(String.join("；", unnamedLooks)).append("。");
        }
        return line.toString();
    }

    /**
     * 烧字幕用的台词正文：去掉「角色名：」，空行与空对白丢弃。无台词返回空列表。
     */
    public static List<String> captionLines(String dialogue) {
        List<String> lines = new ArrayList<>();
        for (SpokenUtterance spoken : spokenUtterances(dialogue)) {
            lines.add(spoken.words());
        }
        return lines;
    }

    static String spokenDialogueConstraint(String dialogue) {
        if (!StringUtils.hasText(dialogue)) {
            return "";
        }
        List<String> contracts = new ArrayList<>();
        for (SpokenUtterance spoken : spokenUtterances(dialogue)) {
            String speaker = spoken.speaker();
            String words = spoken.words();
            if ("旁白".equals(speaker) || "画外音".equals(speaker) || "解说".equals(speaker)) {
                contracts.add("使用清晰自然的" + speaker + "逐字念出：“" + words + "”");
            } else {
                contracts.add("角色“" + speaker + "”逐字说出：“" + words + "”，口型与每个字严格同步");
            }
        }
        StringBuilder line = new StringBuilder("对白执行：");
        if (!contracts.isEmpty()) {
            line.append(String.join("；", contracts)).append("。");
        }
        line.append(DIALOGUE_EXECUTION_TAIL);
        return line.toString();
    }

    static List<SpokenUtterance> spokenUtterances(String dialogue) {
        if (!StringUtils.hasText(dialogue)) {
            return List.of();
        }
        List<SpokenUtterance> utterances = new ArrayList<>();
        for (String rawLine : dialogue.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (!StringUtils.hasText(line)) {
                continue;
            }
            int sep = speakerSeparator(line);
            String speaker;
            String words;
            if (sep > 0 && sep <= 20) {
                speaker = line.substring(0, sep).trim();
                words = line.substring(sep + 1).trim();
            } else {
                speaker = "画面中的说话者";
                words = line;
            }
            if (!StringUtils.hasText(words)) {
                continue;
            }
            utterances.add(new SpokenUtterance(speaker, words));
        }
        return utterances;
    }

    record SpokenUtterance(String speaker, String words) {
    }

    private static List<String> unnamedLooks(
            List<String> names, Set<String> pictured, List<NamedAppearance> unnamedAppearances) {
        List<NamedAppearance> appearances = unnamedAppearances == null ? List.of() : unnamedAppearances;
        List<String> looks = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String name : names) {
            if (pictured.contains(name) || !seen.add(name)) {
                continue;
            }
            String identity = "";
            for (NamedAppearance appearance : appearances) {
                if (appearance != null && name.equals(blank(appearance.name()))) {
                    identity = blank(appearance.identityInvariants());
                    break;
                }
            }
            looks.add(StringUtils.hasText(identity) ? identity : "外貌普通的配角");
        }
        return looks;
    }

    private static int speakerSeparator(String line) {
        int colon = indexOfUnquoted(line, '：');
        int ascii = indexOfUnquoted(line, ':');
        if (colon < 0) {
            return ascii;
        }
        if (ascii < 0) {
            return colon;
        }
        return Math.min(colon, ascii);
    }

    private static int indexOfUnquoted(String line, char sep) {
        return line.indexOf(sep);
    }

    private static void appendLine(StringBuilder prompt, String label, String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        prompt.append(label).append(": ").append(value.trim()).append('\n');
    }

    private static String blank(String value) {
        return value == null ? "" : value.trim();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
