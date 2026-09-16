package cn.aigc.dit.server.drama;

import cn.aigc.dit.generate.GenerateMode;
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
    public static final String I2V_MUST_START =
            "Must start: 以提供的首帧为起幅（空间、已在画面中人物的服装与站位）。严格按 Action 的主语演出，禁止改写谁做了什么。"
                    + "Action 新点名、但首帧里还没有的人，必须按其 Identities 外貌入画；"
                    + "禁止用首帧里已有的人顶替新角色（例如首帧是洛白趴桌、Action 写局长摔档案，必须由局长进门摔档案，不能改成洛白摔档案）。"
                    + "首帧里未被本镜 Action/Cast 点名的人可出画或退到后景。";
    public static final String I2V_IDENTITIES_HEADER = "Identities:";
    public static final String I2V_IDENTITY_FALLBACK =
            "与本镜其他角色及首帧人物不是同一人；按其锁定设定图的性别、年龄、发型、五官、身材和服装入画，禁止把首帧已有人物的脸改贴过来";
    public static final String R2V_CHAIN_HINT =
            "衔接：若本请求附带上一镜落幅参考图，必须从该站位起幅，禁止瞬移，未点名的人保持原位。";
    public static final String PREV_TAIL_NAME = "上一镜落幅";
    public static final String PREV_TAIL_HINT = "是上一镜落幅，必须从该站位起幅，禁止瞬移，未点名的人保持原位";
    public static final String PREV_TAIL_STILL_TYPE = "落幅参考";
    public static final String STILL_PREV_TAIL_HINT =
            "只借上一镜的空间、服装与光线，不继承姿态、构图、机位；必须按 Action 重新构图";
    public static final String STILL_MUST =
            "Must: 这是电影开场静帧，单幅画面。人物已在戏里站好，姿态、站位与构图必须符合 Action 的起幅。禁止三视图、设定表、纯色背景摆拍、无人概念图。";

    private DramaStoryboardCompiler() {
    }

    public record CompileOptions(String videoGenerateMode, boolean chainPrevTail, boolean includePrevTailPicture) {
        public static CompileOptions r2v() {
            return new CompileOptions(GenerateMode.R2V, false, false);
        }

        public static CompileOptions of(String videoGenerateMode, boolean chainPrevTail) {
            return new CompileOptions(DramaVideoGenerateModes.canonical(videoGenerateMode), chainPrevTail, false);
        }

        public CompileOptions withPrevTailPicture() {
            return new CompileOptions(videoGenerateMode(), chainPrevTail(), true);
        }

        public boolean i2v() {
            return DramaVideoGenerateModes.isI2v(videoGenerateMode());
        }

        public boolean r2vChain() {
            return !i2v() && chainPrevTail();
        }
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

    /** 音色描述与 Audio 序号独立于 Picture 序号。 */
    public record AudioReference(String name, String voiceLock, String storageKey) {
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
        return compile(stylePrefix, references, List.of(), shot, CompileOptions.r2v());
    }

    public static Result compile(String stylePrefix, List<ActiveReference> references,
                                 List<AudioReference> audioReferences, ShotContent shot) {
        return compile(stylePrefix, references, audioReferences, shot, CompileOptions.r2v());
    }

    public static Result compile(String stylePrefix, List<ActiveReference> references,
                                 List<AudioReference> audioReferences, ShotContent shot, CompileOptions options) {
        CompileOptions opts = options == null ? CompileOptions.r2v() : options;
        List<ActiveReference> refs = references == null ? List.of() : references;
        List<ActiveReference> pictureRefs = opts.i2v() ? List.of() : refs;
        List<AudioReference> audioRefs = audioReferences == null || opts.i2v() ? List.of() : audioReferences;
        ShotContent content = shot == null
                ? new ShotContent(null, null, null, null, null, DramaContract.DEFAULT_DURATION_SEC, List.of(), 0)
                : shot;
        String prefix = DramaStyles.withVideoSceneClause(StringUtils.hasText(stylePrefix)
                ? stylePrefix.trim()
                : DramaStyles.require(DramaStyles.ANIME_COMIC).prefix());

        StringBuilder prompt = new StringBuilder();
        if (!pictureRefs.isEmpty()) {
            for (int i = 1; i <= pictureRefs.size(); i++) {
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

        if (!pictureRefs.isEmpty()) {
            prompt.append('\n').append("Active References:\n");
            for (int i = 0; i < pictureRefs.size(); i++) {
                ActiveReference ref = pictureRefs.get(i);
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

        if (!audioRefs.isEmpty()) {
            prompt.append('\n').append("Voice References:\n");
            int audioIndex = 0;
            for (AudioReference ref : audioRefs) {
                if (!StringUtils.hasText(ref.storageKey())) continue;
                audioIndex++;
                prompt.append("<Audio ").append(audioIndex).append("> 只定义角色“")
                        .append(blank(ref.name())).append("”的音色身份，不复述参考音频文字，不继承底噪。\n");
            }
        }

        prompt.append('\n').append(MOVIE_SCENE_MUST).append('\n');
        if (opts.i2v()) {
            prompt.append(I2V_MUST_START).append('\n');
            appendI2vIdentities(prompt, refs, content);
        } else if (opts.r2vChain() && !opts.includePrevTailPicture()) {
            prompt.append(R2V_CHAIN_HINT).append('\n');
        }
        Map<String, Integer> pictures = pictureIndexByName(pictureRefs);
        appendLine(prompt, "Scene", bindPictureNames(content.scene(), pictures));
        appendLine(prompt, "Action", bindPictureNames(content.narrativeGoal(), pictures));
        appendLine(prompt, "Dialogue", bindPictureNames(content.dialogue(), pictures));
        appendLine(prompt, "Continuity in", bindPictureNames(content.continuityIn(), pictures));
        appendLine(prompt, "Continuity out", bindPictureNames(content.continuityOut(), pictures));
        prompt.append(castConstraint(content, pictureRefs, opts.i2v())).append('\n');
        prompt.append("Duration: ").append(content.durationSec()).append(" seconds.");
        String spoken = spokenDialogueConstraint(content.dialogue());
        if (StringUtils.hasText(spoken)) {
            String voices = voiceConstraint(audioRefs);
            if (StringUtils.hasText(voices)) {
                prompt.append('\n').append(voices);
            }
            prompt.append('\n').append(spoken);
        }

        return new Result(prompt.toString().trim(), fingerprint(prefix, refs, audioRefs, content, opts));
    }

    /**
     * 开场静帧 i2i：仍使用角色/场景设定图（厂商允许图生图带参考），锁定起幅姿态后再交给 i2v。
     */
    public static Result compileStill(String stylePrefix, List<ActiveReference> references, ShotContent shot) {
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
        prompt.append(STILL_MUST).append('\n');
        Map<String, Integer> pictures = pictureIndexByName(refs);
        appendLine(prompt, "Scene", bindPictureNames(content.scene(), pictures));
        appendLine(prompt, "Action", bindPictureNames(content.narrativeGoal(), pictures));
        appendLine(prompt, "Continuity in", bindPictureNames(content.continuityIn(), pictures));
        prompt.append(castConstraint(content, refs));
        return new Result(prompt.toString().trim(), fingerprint(prefix, refs, List.of(), content, CompileOptions.r2v()));
    }

    public static String fingerprint(String stylePrefix, List<ActiveReference> references, ShotContent shot) {
        return fingerprint(stylePrefix, references, List.of(), shot, CompileOptions.r2v());
    }

    public static String fingerprint(String stylePrefix, List<ActiveReference> references,
                                     List<AudioReference> audioReferences, ShotContent shot) {
        return fingerprint(stylePrefix, references, audioReferences, shot, CompileOptions.r2v());
    }

    public static String fingerprint(String stylePrefix, List<ActiveReference> references,
                                     List<AudioReference> audioReferences, ShotContent shot, CompileOptions options) {
        CompileOptions opts = options == null ? CompileOptions.r2v() : options;
        StringBuilder raw = new StringBuilder();
        raw.append(blank(stylePrefix)).append('\n');
        if (references != null) {
            for (ActiveReference ref : references) {
                raw.append(ref.assetId()).append(':').append(ref.identityVersion()).append(':')
                        .append(blank(ref.identityInvariants())).append('\n');
            }
        }
        if (audioReferences != null && !opts.i2v()) {
            for (AudioReference ref : audioReferences) {
                raw.append("audio:").append(blank(ref.name())).append(':')
                        .append(blank(ref.voiceLock())).append(':')
                        .append(blank(ref.storageKey())).append('\n');
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
        if (opts.i2v() || opts.chainPrevTail()) {
            raw.append('\n').append("mode:").append(DramaVideoGenerateModes.canonical(opts.videoGenerateMode()));
            raw.append('\n').append("chain:").append(opts.chainPrevTail());
        }
        return sha256(raw.toString());
    }

    public static ActiveReference prevTailReference() {
        return new ActiveReference(-1, 0, PREV_TAIL_NAME, "落幅", "", PREV_TAIL_HINT);
    }

    public static ActiveReference prevTailStillReference() {
        return new ActiveReference(-1, 0, PREV_TAIL_NAME, PREV_TAIL_STILL_TYPE, "", STILL_PREV_TAIL_HINT);
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

    static String voiceConstraint(List<AudioReference> references) {
        if (references == null || references.isEmpty()) {
            return "";
        }
        List<String> rules = new ArrayList<>();
        int audioIndex = 0;
        for (AudioReference ref : references) {
            StringBuilder rule = new StringBuilder();
            rule.append("角色“").append(blank(ref.name())).append("”");
            if (StringUtils.hasText(ref.storageKey())) {
                audioIndex++;
                rule.append("使用 <Audio ").append(audioIndex).append("> 的同一音色身份、年龄、口音与音质");
            } else {
                rule.append("的声音必须保持一致");
            }
            if (StringUtils.hasText(ref.voiceLock())) {
                rule.append("，并保持").append(ref.voiceLock().trim());
            }
            rules.add(rule.toString());
        }
        return "Audio: " + String.join("；", rules) + "。除指定对白外不得出现其他人声；保留符合场景的环境声与音效。";
    }

    static String fullIdentity(ActiveReference ref) {
        if (ref == null || !StringUtils.hasText(ref.identityInvariants())) {
            return "";
        }
        return ref.identityInvariants().trim();
    }

    static String pictureHint(ActiveReference ref) {
        if (ref == null) {
            return CHAR_PICTURE_HINT.formatted("该资产");
        }
        if (PREV_TAIL_NAME.equals(blank(ref.name()))) {
            return PREV_TAIL_STILL_TYPE.equals(blank(ref.type())) ? STILL_PREV_TAIL_HINT : PREV_TAIL_HINT;
        }
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
        return castConstraint(shot, references, false);
    }

    static String castConstraint(ShotContent shot, List<ActiveReference> references, boolean i2v) {
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
        List<String> unnamedLooks = unnamedLooks(names, pictured, shot.unnamedAppearances(), i2v);

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
            List<String> names, Set<String> pictured, List<NamedAppearance> unnamedAppearances, boolean i2v) {
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
            if (i2v) {
                looks.add(i2vIdentity(name, identity));
            } else {
                looks.add(StringUtils.hasText(identity)
                        ? identity
                        : "角色“" + name + "”，外貌按剧本，不得被其他人顶替");
            }
        }
        return looks;
    }

    static boolean genericIdentityLock(String identity) {
        if (!StringUtils.hasText(identity)) {
            return true;
        }
        String text = identity.trim();
        return text.contains("外貌、发型、服装和年龄必须保持一致")
                || text.contains("与主身份同一张脸和发型");
    }

    static String i2vIdentity(String name, String identity) {
        if (!genericIdentityLock(identity)) {
            return identity.trim();
        }
        String who = StringUtils.hasText(name) ? name.trim() : "该角色";
        return "角色“" + who + "”，" + I2V_IDENTITY_FALLBACK;
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

    private static void appendI2vIdentities(StringBuilder prompt, List<ActiveReference> refs, ShotContent content) {
        Map<String, String> byName = new LinkedHashMap<>();
        if (refs != null) {
            for (ActiveReference ref : refs) {
                if (ref == null
                        || !DramaContract.ASSET_TYPE_CHARACTER.equals(ref.type())
                        || !StringUtils.hasText(ref.name())) {
                    continue;
                }
                byName.put(ref.name().trim(), fullIdentity(ref));
            }
        }
        if (content != null && content.unnamedAppearances() != null) {
            for (NamedAppearance appearance : content.unnamedAppearances()) {
                if (appearance == null || !StringUtils.hasText(appearance.name())) {
                    continue;
                }
                String name = appearance.name().trim();
                if (byName.containsKey(name) && StringUtils.hasText(byName.get(name))) {
                    continue;
                }
                String identity = blank(appearance.identityInvariants());
                if (StringUtils.hasText(identity) || !byName.containsKey(name)) {
                    byName.put(name, identity);
                }
            }
        }
        if (content != null && content.characterNames() != null) {
            for (String name : content.characterNames()) {
                if (StringUtils.hasText(name) && !byName.containsKey(name.trim())) {
                    byName.put(name.trim(), "");
                }
            }
        }
        if (byName.isEmpty()) {
            return;
        }
        prompt.append(I2V_IDENTITIES_HEADER).append('\n');
        for (Map.Entry<String, String> entry : byName.entrySet()) {
            String identity = i2vIdentity(entry.getKey(), blank(entry.getValue()));
            prompt.append(entry.getKey()).append("：").append(identity);
            if (!identity.endsWith("。") && !identity.endsWith(".")) {
                prompt.append('。');
            }
            prompt.append('\n');
        }
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
