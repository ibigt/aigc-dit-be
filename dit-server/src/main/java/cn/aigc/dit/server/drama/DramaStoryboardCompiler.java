package cn.aigc.dit.server.drama;

import cn.aigc.dit.generate.GenerateMode;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    /**
     * 片头片尾稳住：减轻起幅突变脸/构图、落幅乱推远，便于链式衔接。
     * 所有风格视频编译都追加。
     */
    public static final String CLIP_EDGE_STABILITY =
            "Must: 片头约前 0.5 秒内保持起幅构图、人物脸型与服装稳定，禁止开场突变脸、闪切构图或瞬移；"
                    + "片尾停在稳定姿态与景别上，禁止落幅前突然推远、甩镜或改脸，便于与下一镜衔接。";
    /** 真人写实 / 真人3D 前缀才追加。对白用中近景，全景只留给明确写出的交代镜头。 */
    public static final String LIVE_ACTION_COVERAGE =
            "景别：对白和问责用中近景或过肩拍正在说话的人，全景只留给明确写出全景或远景的交代镜头。"
                    + "禁止为了让每个人同时入画而拉成整间房。场景参考图只提供空间和光线，不决定机位和构图。"
                    + "未开口的人留在画外或只出肩背。";
    public static final String DIALOGUE_EXECUTION_TAIL = "只念台词正文，不念角色名和冒号，不得增删或改写。";
    public static final String NARRATION_MOUTH_CLOSED = "画面中角色保持闭嘴，不对旁白与画外音台词对口型。";
    public static final String DIALOGUE_PERFORMANCE_PREFIX = "对白表演：表情与语气一致，呈现";
    public static final String DIALOGUE_PERFORMANCE_SUFFIX = "神态，动作自然克制，不夸张演过头。";
    /** 有镜头序列时力度由镜头动作自己写，对白表演只约束表情和口型。 */
    public static final String DIALOGUE_PERFORMANCE_SUFFIX_BEATS = "神态与口型。";
    /** 台词已经写在各镜头里时，文末只保留怎么念，不再把原句复述一遍。 */
    public static final String BEAT_DIALOGUE_EXECUTION =
            "只念各镜头台词正文，不念角色名和冒号，不得增删或改写。";
    public static final String BEAT_NO_SPEECH = "本镜头无人开口";
    public static final String BEAT_LIP_SYNC = "口型与这句同步";
    /** 环境声为空时仍留在可见正文上，避免配乐约束只出现在用户看不到的文末。 */
    static final String VISIBLE_NO_SCORE = "不要配乐，只保留对白。";
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
            List<NamedAppearance> unnamedAppearances,
            String camera,
            String ambiance,
            DramaShotBeats shotBeats
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
                    characterNames, castCount, List.of(), null, null, null);
        }

        public ShotContent(
                String scene,
                String narrativeGoal,
                String dialogue,
                String continuityIn,
                String continuityOut,
                int durationSec,
                List<String> characterNames,
                int castCount,
                List<NamedAppearance> unnamedAppearances) {
            this(scene, narrativeGoal, dialogue, continuityIn, continuityOut, durationSec,
                    characterNames, castCount, unnamedAppearances, null, null, null);
        }

        public ShotContent(
                String scene,
                String narrativeGoal,
                String dialogue,
                String continuityIn,
                String continuityOut,
                int durationSec,
                List<String> characterNames,
                int castCount,
                List<NamedAppearance> unnamedAppearances,
                String camera,
                String ambiance) {
            this(scene, narrativeGoal, dialogue, continuityIn, continuityOut, durationSec,
                    characterNames, castCount, unnamedAppearances, camera, ambiance, null);
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
        appendReferenceDeclaration(prompt, pictureRefs);

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
        prompt.append(CLIP_EDGE_STABILITY).append('\n');
        if (needsLiveActionCoverage(prefix)) {
            prompt.append(LIVE_ACTION_COVERAGE).append('\n');
        }
        if (opts.i2v()) {
            prompt.append(I2V_MUST_START).append('\n');
            appendI2vIdentities(prompt, refs, content);
        } else if (opts.r2vChain() && !opts.includePrevTailPicture()) {
            prompt.append(R2V_CHAIN_HINT).append('\n');
        }
        Map<String, Integer> pictures = pictureIndexByName(pictureRefs);
        boolean beats = DramaShotBeats.hasBeats(content.shotBeats());
        boolean beatsSpeak = beats && beatsHaveSpoken(content.shotBeats());
        appendVisualLines(prompt, content, pictures, beatsSpeak);
        if (!beats) {
            appendLine(prompt, "Dialogue", bindPictureNames(content.dialogue(), pictures));
        }
        appendLine(prompt, "Continuity in", bindPictureNames(content.continuityIn(), pictures));
        appendLine(prompt, "Continuity out", bindPictureNames(content.continuityOut(), pictures));
        prompt.append(castConstraint(content, pictureRefs, opts.i2v(), beats)).append('\n');
        prompt.append("Duration: ").append(content.durationSec()).append(" seconds.");
        String spoken = beatsSpeak
                ? beatDialogueGlobalRule(content.shotBeats())
                : spokenDialogueConstraint(content.dialogue(), beats);
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
        appendReferenceDeclaration(prompt, refs);
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
        appendVisualLines(prompt, content, pictures, false);
        appendLine(prompt, "Continuity in", bindPictureNames(content.continuityIn(), pictures));
        prompt.append(castConstraint(content, refs, false, DramaShotBeats.hasBeats(content.shotBeats())));
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
            raw.append(blank(shot.camera())).append('\n');
            raw.append(blank(shot.narrativeGoal())).append('\n');
            raw.append(blank(shot.ambiance())).append('\n');
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
            if (shot.shotBeats() != null && shot.shotBeats().getBeats() != null) {
                raw.append('\n').append(blank(shot.shotBeats().getBlocking()));
                for (DramaShotBeats.Beat beat : shot.shotBeats().getBeats()) {
                    if (beat == null) {
                        continue;
                    }
                    raw.append('\n').append(blank(beat.getCamera()))
                            .append('|').append(blank(beat.getAction()))
                            .append('|').append(blank(beat.getContinuity()))
                            .append('|').append(blank(beat.getDialogue()));
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
        return castConstraint(shot, references, false, false);
    }

    static String castConstraint(ShotContent shot, List<ActiveReference> references, boolean i2v) {
        return castConstraint(shot, references, i2v, false);
    }

    static String castConstraint(ShotContent shot, List<ActiveReference> references, boolean i2v, boolean beats) {
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
        if (beats) {
            line.append("Cast: 本镜出场人物为");
            line.append(castNameList(names, pictureIndexByName(references))).append("。");
            line.append("某一镜头没点名的人保持起始站位或留在画外，禁止突然入画。");
        } else {
            line.append("Cast: 本镜恰好 ").append(count).append(" 人，不得增删角色。");
            if (!picturedNames.isEmpty()) {
                line.append("出场：");
                line.append(castNameList(picturedNames, pictureIndexByName(references))).append("。");
            }
        }
        if (!unnamedLooks.isEmpty()) {
            line.append("未出图角色按外貌出演：").append(String.join("；", unnamedLooks)).append("。");
        }
        return line.toString();
    }

    private static String castNameList(List<String> names, Map<String, Integer> pictures) {
        List<String> labeled = new ArrayList<>();
        for (String name : names) {
            Integer picture = pictures == null ? null : pictures.get(name);
            labeled.add(picture == null ? name : name + "=<Picture " + picture + ">");
        }
        return String.join("、", labeled);
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

    /**
     * 口播字数：汉字、字母、数字各计 1；标点、省略号、引号与空白不发音，不计。
     * 口播速率按可念字标定（约 4 字/秒），连标点一起数会虚增时长、多拆镜头。
     */
    public static int speakableChars(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetterOrDigit(text.charAt(i))) {
                count++;
            }
        }
        return count;
    }

    static boolean beatsHaveSpoken(DramaShotBeats beats) {
        if (beats == null || beats.getBeats() == null) {
            return false;
        }
        for (DramaShotBeats.Beat beat : beats.getBeats()) {
            if (beat != null && !spokenUtterances(beat.getDialogue()).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 镜头序列里的台词只在对应镜头出现一次。文末保留念法，并在有旁白或画外音时要求闭嘴。
     */
    static String beatDialogueGlobalRule(DramaShotBeats beats) {
        boolean offScreen = false;
        if (beats != null && beats.getBeats() != null) {
            for (DramaShotBeats.Beat beat : beats.getBeats()) {
                if (beat == null) {
                    continue;
                }
                for (SpokenUtterance spoken : spokenUtterances(beat.getDialogue())) {
                    if (spoken.narration() || spoken.voiceOver()) {
                        offScreen = true;
                        break;
                    }
                }
            }
        }
        StringBuilder line = new StringBuilder("对白执行：").append(BEAT_DIALOGUE_EXECUTION);
        if (offScreen) {
            line.append(NARRATION_MOUTH_CLOSED);
        }
        return line.toString();
    }

    static String beatDialogueText(String dialogue) {
        List<SpokenUtterance> utterances = spokenUtterances(dialogue);
        if (utterances.isEmpty()) {
            return BEAT_NO_SPEECH;
        }
        List<String> parts = new ArrayList<>();
        for (SpokenUtterance spoken : utterances) {
            parts.add(beatUtteranceClause(spoken));
        }
        return String.join("；", parts);
    }

    private static String beatUtteranceClause(SpokenUtterance spoken) {
        String words = spoken.words();
        if (spoken.narration()) {
            return "旁白解说员以全片统一的旁白音色逐字念出：“" + words
                    + "”，该音色不与任何角色混淆，画面中人物闭嘴，不对口型";
        }
        if (spoken.voiceOver()) {
            String speaker = spoken.speaker();
            return "角色“" + speaker + "”的画外音（使用" + speaker + "的音色，自画面外传来）逐字念出：“"
                    + words + "”，画面中人物闭嘴，不对口型";
        }
        String speaker = spoken.speaker();
        if (StringUtils.hasText(spoken.emotion())) {
            return "角色“" + speaker + "”以" + spoken.emotion() + "的语气逐字说出：“" + words + "”，" + BEAT_LIP_SYNC;
        }
        return "角色“" + speaker + "”逐字说出：“" + words + "”，" + BEAT_LIP_SYNC;
    }

    static String spokenDialogueConstraint(String dialogue) {
        return spokenDialogueConstraint(dialogue, false);
    }

    /**
     * 文末只留执行约束。情绪、原句、画外音位置和环境声已经写在可见正文里，这里不再复述。
     * {@code beats} 保留给调用方；有镜头时走 {@link #beatDialogueGlobalRule}，不再从这里补语气。
     */
    static String spokenDialogueConstraint(String dialogue, boolean beats) {
        if (!StringUtils.hasText(dialogue)) {
            return "";
        }
        List<SpokenUtterance> utterances = spokenUtterances(dialogue);
        if (utterances.isEmpty()) {
            return "";
        }
        boolean offScreen = false;
        boolean onScreen = false;
        for (SpokenUtterance spoken : utterances) {
            if (spoken.narration() || spoken.voiceOver()) {
                offScreen = true;
            } else {
                onScreen = true;
            }
        }
        StringBuilder line = new StringBuilder("对白执行：");
        if (onScreen) {
            line.append(BEAT_LIP_SYNC).append('。');
        }
        line.append(beats ? BEAT_DIALOGUE_EXECUTION : DIALOGUE_EXECUTION_TAIL);
        if (offScreen) {
            line.append(NARRATION_MOUTH_CLOSED);
        }
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
            String speakerRaw;
            String words;
            if (sep > 0 && sep <= 32) {
                speakerRaw = line.substring(0, sep).trim();
                words = line.substring(sep + 1).trim();
            } else {
                speakerRaw = "画面中的说话者";
                words = line;
            }
            if (!StringUtils.hasText(words)) {
                continue;
            }
            SpeakerEmotion parsed = parseSpeakerEmotion(speakerRaw);
            String speaker = DramaDialogueSpeakers.normalizeSpeaker(parsed.speaker());
            boolean narrationSpeaker = DramaDialogueSpeakers.isNarration(speaker);
            // 「角色名（画外音）：原文」——说话人是真实角色，括号标记表示用该角色音色在画面外念出。
            boolean voiceOver = !narrationSpeaker && DramaDialogueSpeakers.isVoiceOverMarker(parsed.emotion());
            String emotion = narrationSpeaker || voiceOver
                    ? null
                    : DramaDialogueSpeakers.sanitizeEmotion(parsed.emotion());
            utterances.add(new SpokenUtterance(
                    speaker,
                    words,
                    emotion,
                    narrationSpeaker,
                    voiceOver));
        }
        return utterances;
    }

    private static SpeakerEmotion parseSpeakerEmotion(String raw) {
        if (!StringUtils.hasText(raw)) {
            return new SpeakerEmotion("", null);
        }
        String text = raw.trim();
        int openCn = text.lastIndexOf('（');
        int closeCn = text.lastIndexOf('）');
        if (openCn > 0 && closeCn == text.length() - 1 && closeCn > openCn) {
            return new SpeakerEmotion(
                    text.substring(0, openCn).trim(),
                    text.substring(openCn + 1, closeCn).trim());
        }
        int openEn = text.lastIndexOf('(');
        int closeEn = text.lastIndexOf(')');
        if (openEn > 0 && closeEn == text.length() - 1 && closeEn > openEn) {
            return new SpeakerEmotion(
                    text.substring(0, openEn).trim(),
                    text.substring(openEn + 1, closeEn).trim());
        }
        return new SpeakerEmotion(text, null);
    }

    record SpokenUtterance(String speaker, String words, String emotion, boolean narration, boolean voiceOver) {
        SpokenUtterance(String speaker, String words) {
            this(speaker, words, null, DramaDialogueSpeakers.isNarration(speaker), false);
        }
    }

    private record SpeakerEmotion(String speaker, String emotion) {
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

    private static void appendVisualLines(
            StringBuilder prompt, ShotContent content, Map<String, Integer> pictures, boolean performBeatDialogue) {
        appendLine(prompt, "Scene", bindPictureNames(content.scene(), pictures));
        if (DramaShotBeats.hasBeats(content.shotBeats())) {
            appendLine(prompt, "站位", bindPictureNames(content.shotBeats().getBlocking(), pictures));
            int index = 1;
            for (DramaShotBeats.Beat beat : content.shotBeats().getBeats()) {
                if (beat == null) {
                    continue;
                }
                StringBuilder block = new StringBuilder();
                appendBeatClause(block, "景别", beat.getCamera(), pictures);
                appendBeatClause(block, "动作", beat.getAction(), pictures);
                appendBeatClause(block, "接续", beat.getContinuity(), pictures);
                String dialogue = performBeatDialogue ? beatDialogueText(beat.getDialogue()) : beat.getDialogue();
                appendBeatClause(block, "台词", dialogue, pictures);
                if (!block.isEmpty()) {
                    prompt.append("镜头").append(index).append(": ").append(block).append('\n');
                }
                index++;
            }
            appendLine(prompt, "Ambiance", bindPictureNames(content.ambiance(), pictures));
            return;
        }
        appendLine(prompt, "Camera", bindPictureNames(content.camera(), pictures));
        appendLine(prompt, "Action", bindPictureNames(content.narrativeGoal(), pictures));
        appendLine(prompt, "Ambiance", bindPictureNames(content.ambiance(), pictures));
    }

    private static void appendBeatClause(
            StringBuilder block, String label, String value, Map<String, Integer> pictures) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        if (!block.isEmpty()) {
            block.append(' ');
        }
        block.append(label).append('：').append(bindPictureNames(value.trim(), pictures)).append('。');
    }

    /** 风格句后按参考图类型分组，编号沿用已发出的 Picture 序号。没有参考图时不写。 */
    private static void appendReferenceDeclaration(StringBuilder prompt, List<ActiveReference> refs) {
        if (refs == null || refs.isEmpty()) {
            return;
        }
        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < refs.size(); i++) {
            groups.computeIfAbsent(referenceKind(refs.get(i)), key -> new ArrayList<>()).add(i + 1);
        }
        prompt.append("参考图：");
        boolean first = true;
        for (Map.Entry<String, List<Integer>> group : groups.entrySet()) {
            if (!first) {
                prompt.append('；');
            }
            first = false;
            List<Integer> indexes = group.getValue();
            for (int i = 0; i < indexes.size(); i++) {
                if (i > 0) {
                    prompt.append('、');
                }
                prompt.append("<Picture ").append(indexes.get(i)).append('>');
            }
            prompt.append(" 为").append(group.getKey());
        }
        prompt.append("。\n");
    }

    private static String referenceKind(ActiveReference ref) {
        if (ref == null) {
            return "角色参考图";
        }
        if (PREV_TAIL_NAME.equals(blank(ref.name()))) {
            return "上一镜落幅参考图";
        }
        if (DramaContract.ASSET_TYPE_SCENE.equals(ref.type())) {
            return "场景参考图";
        }
        if (DramaContract.ASSET_TYPE_PROP.equals(ref.type())) {
            return "道具参考图";
        }
        return "角色参考图";
    }

    private static void appendLine(StringBuilder prompt, String label, String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        prompt.append(label).append(": ").append(value.trim()).append('\n');
    }

    /**
     * 真人写实（写真实拍）与真人3D（极致写实实拍）都需要中近景对白约束，避免场景参考图把机位拉成整间房。
     */
    static boolean needsLiveActionCoverage(String stylePrefix) {
        if (!StringUtils.hasText(stylePrefix)) {
            return false;
        }
        String prefix = stylePrefix.trim();
        return prefix.contains("极致写实实拍摄影")
                || prefix.contains("真人实拍写真摄影")
                || prefix.contains("真人写真")
                || prefix.contains("半写实3D渲染")
                || prefix.contains("高精度二次元写实");
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

    private static final Pattern VISIBLE_LABEL = Pattern.compile(
            "(站位|镜头\\d+|景别|动作|接续|台词|场景|运镜|环境声)\\s*[:：]");

    /**
     * 旧稿仍认的口播标记。冒号紧挨引号时不依赖这些词，见 {@link #findSpeechHits}。
     * 旁白、画外音不加引号，仍收到换行。
     */
    private static final Pattern LEGACY_SPEECH = Pattern.compile("说\\s*[:：]|旁白\\s*[:：]|（画外音）\\s*[:：]");

    /**
     * 给用户看的核心正文。场景单独一行；站位、景别和接续留在镜头说明里。
     * 动作和该角色的下一句台词写成「@名字 动作，说：“台词”」。口播里的名字不加 {@code @}。
     * 不含风格、参考图声明和口播模板。
     */
    public static String renderVisiblePrompt(ShotContent content, List<String> imagedNames) {
        return renderVisiblePrompt(content, imagedNames, null);
    }

    public static String renderVisiblePrompt(ShotContent content, List<String> imagedNames, String locationName) {
        return renderVisiblePrompt(content, imagedNames, locationName, List.of());
    }

    /**
     * @param propNames 本镜要用的道具。引用名单里有、画面说明里没写出名字的，补到站位行。
     */
    public static String renderVisiblePrompt(
            ShotContent content, List<String> imagedNames, String locationName, List<String> propNames) {
        ShotContent shot = content == null
                ? new ShotContent(null, null, null, null, null, DramaContract.DEFAULT_DURATION_SEC, List.of(), 0)
                : content;
        List<String> names = normalizeNames(imagedNames);
        StringBuilder body = new StringBuilder();
        if (DramaShotBeats.hasBeats(shot.shotBeats())) {
            appendVisibleLine(body, "场景", sceneLine(shot.scene(), locationName, names));
            appendVisibleLine(body, "站位", markAtNames(shot.shotBeats().getBlocking(), names));
            int index = 1;
            for (DramaShotBeats.Beat beat : shot.shotBeats().getBeats()) {
                if (beat == null) {
                    continue;
                }
                String header = cameraHeader(
                        markAtNames(beat.getCamera(), names),
                        markAtNames(beat.getContinuity(), names));
                List<String> lines = performanceLines(
                        beat.getAction(), beat.getDialogue(), names, shot.characterNames());
                if (!StringUtils.hasText(header) && lines.isEmpty()) {
                    continue;
                }
                if (!body.isEmpty()) {
                    // 同一镜头内的动作和对白只换行；不同镜头之间留一行空行。
                    body.append(index > 1 ? "\n\n" : "\n");
                }
                body.append("镜头").append(index).append('：').append(header);
                for (String line : lines) {
                    body.append('\n').append(line);
                }
                if (spokenUtterances(beat.getDialogue()).isEmpty()) {
                    body.append('\n').append(BEAT_NO_SPEECH).append('。');
                }
                index++;
            }
            appendVisibleAmbiance(body, shot.ambiance(), names);
        } else {
            appendVisibleLine(body, "场景", sceneLine(shot.scene(), locationName, names));
            appendVisibleLine(body, "运镜", markAtNames(shot.camera(), names));
            if (spokenUtterances(shot.dialogue()).isEmpty()) {
                appendVisibleLine(body, "动作", markAtNames(shot.narrativeGoal(), names));
            } else {
                for (String line : performanceLines(shot.narrativeGoal(), shot.dialogue(), names, shot.characterNames())) {
                    if (!body.isEmpty()) {
                        body.append('\n');
                    }
                    body.append(line);
                }
            }
            appendVisibleAmbiance(body, shot.ambiance(), names);
        }
        citeMissingProps(body, propNames, names);
        return quoteSpokenLines(body.toString().trim());
    }

    /**
     * 本镜道具名单里、引用名单里有、正文还没有 {@code @名字} 的道具，补到站位行。
     * 动作里已经写过的不重复补。不在引用名单里的不写 {@code @}。
     */
    private static void citeMissingProps(StringBuilder body, List<String> propNames, List<String> imagedNames) {
        if (propNames == null || propNames.isEmpty()) {
            return;
        }
        List<String> missing = new ArrayList<>();
        for (String raw : propNames) {
            if (!StringUtils.hasText(raw)) {
                continue;
            }
            String name = raw.trim();
            if (imagedNames == null || !imagedNames.contains(name) || containsAtName(body, name, imagedNames)) {
                continue;
            }
            if (!missing.contains(name)) {
                missing.add(name);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        StringBuilder mention = new StringBuilder();
        for (String name : missing) {
            if (!mention.isEmpty()) {
                mention.append('，');
            }
            mention.append('@').append(name);
        }
        int label = labelIndex(body, "站位");
        if (label >= 0) {
            int lineEnd = newlineAt(body, label);
            int end = lineEnd < 0 ? body.length() : lineEnd;
            end = trimTrailingPunct(body, label, end);
            if (hasContentAfterLabel(body, label, end)) {
                body.insert(end, '，');
                end++;
            }
            body.insert(end, mention);
            body.insert(end + mention.length(), '。');
            return;
        }
        String line = "站位：" + mention + "。";
        int scene = labelIndex(body, "场景");
        if (scene >= 0) {
            int lineEnd = newlineAt(body, scene);
            if (lineEnd < 0) {
                body.append('\n').append(line);
            } else {
                body.insert(lineEnd, "\n" + line);
            }
            return;
        }
        if (body.isEmpty()) {
            body.append(line);
        } else {
            body.insert(0, line + "\n");
        }
    }

    /** {@code @怀表开关} 也算已经引用了「怀表」；{@code @档案袋} 不算「档案」。 */
    private static boolean containsAtName(CharSequence text, String name, List<String> imagedNames) {
        if (!StringUtils.hasText(name) || text == null) {
            return false;
        }
        String source = text.toString();
        String token = "@" + name;
        int from = 0;
        while (from < source.length()) {
            int at = source.indexOf(token, from);
            if (at < 0) {
                return false;
            }
            String matched = longestNameAt(source, at + 1, imagedNames);
            if (name.equals(matched)) {
                return true;
            }
            from = at + 1;
        }
        return false;
    }

    private static int labelIndex(CharSequence text, String label) {
        String source = text.toString();
        for (String mark : List.of(label + "：", label + ":")) {
            int from = 0;
            while (from < source.length()) {
                int found = source.indexOf(mark, from);
                if (found < 0) {
                    break;
                }
                if (found == 0 || source.charAt(found - 1) == '\n') {
                    return found;
                }
                from = found + 1;
            }
        }
        return -1;
    }

    private static int newlineAt(CharSequence text, int from) {
        for (int i = Math.max(from, 0); i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static int trimTrailingPunct(StringBuilder body, int from, int end) {
        int index = end;
        while (index > from) {
            char value = body.charAt(index - 1);
            if (value != '。' && value != '.' && value != ' ' && value != '，' && value != ',') {
                break;
            }
            index--;
        }
        if (index < end) {
            body.delete(index, end);
        }
        return index;
    }

    private static boolean hasContentAfterLabel(CharSequence body, int labelAt, int end) {
        int colon = indexOf(body, '：', labelAt, end);
        if (colon < 0) {
            colon = indexOf(body, ':', labelAt, end);
        }
        int start = colon < 0 ? end : colon + 1;
        for (int i = start; i < end; i++) {
            char value = body.charAt(i);
            if (value != ' ' && value != '。' && value != '.' && value != '，' && value != ',') {
                return true;
            }
        }
        return false;
    }

    private static int indexOf(CharSequence text, char value, int from, int end) {
        for (int i = Math.max(from, 0); i < end && i < text.length(); i++) {
            if (text.charAt(i) == value) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 出片拼装：可见正文作画面和台词核心，叙述里的 {@code @名字} 换成 Picture，台词保持普通名字。
     * 不追加上一镜尾帧。
     */
    public static Result assemble(String stylePrefix, List<ActiveReference> references,
                                  List<AudioReference> audioReferences, String visiblePrompt, ShotContent shot) {
        CompileOptions opts = CompileOptions.r2v();
        List<ActiveReference> refs = references == null ? List.of() : references;
        List<AudioReference> audioRefs = audioReferences == null ? List.of() : audioReferences;
        ShotContent content = shot == null
                ? new ShotContent(null, null, null, null, null, DramaContract.DEFAULT_DURATION_SEC, List.of(), 0)
                : shot;
        String prefix = DramaStyles.withVideoSceneClause(StringUtils.hasText(stylePrefix)
                ? stylePrefix.trim()
                : DramaStyles.require(DramaStyles.ANIME_COMIC).prefix());
        String visible = quoteSpokenLines(visiblePrompt == null ? "" : visiblePrompt.trim());
        Map<String, Integer> pictures = pictureIndexByName(refs);

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
        appendReferenceDeclaration(prompt, refs);
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
        if (!audioRefs.isEmpty()) {
            prompt.append('\n').append("Voice References:\n");
            int audioIndex = 0;
            for (AudioReference ref : audioRefs) {
                if (!StringUtils.hasText(ref.storageKey())) {
                    continue;
                }
                audioIndex++;
                prompt.append("<Audio ").append(audioIndex).append("> 只定义角色“")
                        .append(blank(ref.name())).append("”的音色身份，不复述参考音频文字，不继承底噪。\n");
            }
        }
        prompt.append('\n').append(MOVIE_SCENE_MUST).append('\n');
        prompt.append(CLIP_EDGE_STABILITY).append('\n');
        if (needsLiveActionCoverage(prefix)) {
            prompt.append(LIVE_ACTION_COVERAGE).append('\n');
        }
        if (StringUtils.hasText(visible)) {
            prompt.append(bindAtMentions(visible, pictures)).append('\n');
        }
        boolean beats = DramaShotBeats.hasBeats(content.shotBeats());
        boolean beatsSpeak = beats && beatsHaveSpoken(content.shotBeats());
        appendLine(prompt, "Continuity in", bindPictureNames(content.continuityIn(), pictures));
        appendLine(prompt, "Continuity out", bindPictureNames(content.continuityOut(), pictures));
        prompt.append(castConstraint(content, refs, false, beats)).append('\n');
        prompt.append("Duration: ").append(content.durationSec()).append(" seconds.");
        String spoken = beatsSpeak
                ? beatDialogueGlobalRule(content.shotBeats())
                : spokenDialogueConstraint(plainDialogue(content.dialogue()), false);
        if (StringUtils.hasText(spoken)) {
            String voices = voiceConstraint(audioRefs);
            if (StringUtils.hasText(voices)) {
                prompt.append('\n').append(voices);
            }
            prompt.append('\n').append(spoken);
        }
        return new Result(prompt.toString().trim(), fingerprintVisible(prefix, refs, audioRefs, content, visible));
    }

    public static String fingerprintVisible(String stylePrefix, List<ActiveReference> references,
                                            List<AudioReference> audioReferences, ShotContent shot,
                                            String visiblePrompt) {
        String base = fingerprint(stylePrefix, references, audioReferences, shot, CompileOptions.r2v());
        return sha256(base + "\nvisible:" + blank(unquoteSpokenLines(visiblePrompt)));
    }

    /** 叙述部分按首次 {@code @} 去重。{@code knownNames} 用于最长匹配，可为空。 */
    public static List<String> narrativeAtNames(String text, List<String> knownNames) {
        List<String> names = new ArrayList<>();
        if (!StringUtils.hasText(text)) {
            return names;
        }
        List<String> known = normalizeNames(knownNames);
        for (TextSpan span : splitSpans(text)) {
            if (span.dialogue()) {
                continue;
            }
            collectAtNames(span.text(), known, names);
        }
        return names;
    }

    public static boolean hasShotLabels(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        return Pattern.compile("镜头\\d+\\s*[:：]").matcher(text).find();
    }

    /** 去掉全部 {@code @} 前缀，名字本身保留。台词框保存前使用。 */
    public static String stripMentionMarks(String text) {
        return stripAtPrefix(text);
    }

    /** 去掉台词段里的 {@code @}，名字本身保留。 */
    public static String stripDialogueMentions(String text) {
        if (!StringUtils.hasText(text)) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        for (TextSpan span : splitSpans(text)) {
            out.append(span.dialogue() ? stripAtPrefix(span.text()) : span.text());
        }
        return out.toString();
    }

    /** 正文里有镜头序列，或有「说：」/旁白/画外音口播位。 */
    public static boolean hasDialogueSlots(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        return hasShotLabels(text) || !saySlots(text).isEmpty();
    }

    /** 有镜头序列或口播位时按出现顺序汇总台词；否则返回空，交给台词框。 */
    public static String extractDialogue(String visiblePrompt) {
        if (!hasDialogueSlots(visiblePrompt)) {
            return null;
        }
        List<TextSpan> spans = splitSpans(visiblePrompt);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < spans.size(); i++) {
            TextSpan span = spans.get(i);
            if (!span.dialogue()) {
                continue;
            }
            if (isLegacyDialogueSpan(span.text())) {
                String body = dialogueBody(span.text());
                if (StringUtils.hasText(body)) {
                    lines.add(body.trim());
                }
                continue;
            }
            String words = stripAtPrefix(span.text()).trim();
            String narrative = previousNarrative(spans, i);
            if (precededBySay(narrative)) {
                words = stripWrappingQuotes(words);
            }
            words = words + punctuationAfterClosingQuote(spans, i);
            if (!StringUtils.hasText(words)) {
                continue;
            }
            String speaker = speakerFromNarrative(narrative);
            String emotion = emotionAdverbBefore(narrative);
            if (StringUtils.hasText(emotion) && StringUtils.hasText(speaker)
                    && !speaker.startsWith("旁白") && !speaker.contains("画外音")) {
                speaker = speaker + "（" + emotion + "）";
            }
            lines.add(StringUtils.hasText(speaker) ? speaker + "：" + words : words);
        }
        return lines.isEmpty() ? null : String.join("\n", lines);
    }

    /**
     * 把台词框写回核心正文里的口播位置，不加 {@code @}。
     * 没有镜头序列、也没有「说：」时原样返回正文。
     */
    public static String writeDialogueBack(String visiblePrompt, String dialogue) {
        String visible = visiblePrompt == null ? "" : visiblePrompt;
        if (!saySlots(visible).isEmpty()) {
            return quoteSpokenLines(writeSayDialogue(visible, dialogue));
        }
        if (!hasShotLabels(visible)) {
            return visible;
        }
        List<String> lines = dialogueLines(dialogue);
        Matcher matcher = VISIBLE_LABEL.matcher(visible);
        List<LabelHit> labels = new ArrayList<>();
        while (matcher.find()) {
            labels.add(new LabelHit(matcher.start(), matcher.end(), matcher.group(1)));
        }
        List<Integer> dialogueLabels = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) {
            if ("台词".equals(labels.get(i).name())) {
                dialogueLabels.add(i);
            }
        }
        if (dialogueLabels.isEmpty()) {
            return appendDialogueClauses(visible, labels, lines);
        }
        StringBuilder out = new StringBuilder();
        int cursor = 0;
        for (int slot = 0; slot < dialogueLabels.size(); slot++) {
            LabelHit label = labels.get(dialogueLabels.get(slot));
            int contentEnd = dialogueLabels.get(slot) + 1 < labels.size()
                    ? labels.get(dialogueLabels.get(slot) + 1).start()
                    : nextLabelAfter(labels, dialogueLabels.get(slot), visible.length());
            out.append(visible, cursor, label.end());
            if (slot < lines.size() && StringUtils.hasText(lines.get(slot))) {
                out.append(lines.get(slot).trim());
            }
            cursor = contentEnd;
        }
        if (cursor < visible.length()) {
            out.append(visible.substring(cursor));
        }
        if (lines.size() > dialogueLabels.size()) {
            String extra = String.join("\n", lines.subList(dialogueLabels.size(), lines.size()));
            if (StringUtils.hasText(extra)) {
                out.append('\n').append("台词：").append(extra.trim());
            }
        }
        return out.toString().trim();
    }

    /** 从旧的整段覆盖里抽出可读段落，不还原成结构化镜头。 */
    public static String readableLegacyOverride(String override) {
        if (!StringUtils.hasText(override)) {
            return "";
        }
        String[] lines = override.split("\\r?\\n");
        List<String> kept = new ArrayList<>();
        boolean skipping = false;
        for (String raw : lines) {
            String line = raw == null ? "" : raw.trim();
            if (!StringUtils.hasText(line)) {
                skipping = false;
                continue;
            }
            if (startsBlock(line)) {
                skipping = true;
                continue;
            }
            if (skipping) {
                if (isVisibleContentLine(line)) {
                    skipping = false;
                } else {
                    continue;
                }
            }
            if (isTemplateLine(line)) {
                continue;
            }
            kept.add(line);
        }
        if (!kept.isEmpty() && !isVisibleContentLine(kept.get(0)) && kept.stream().anyMatch(DramaStoryboardCompiler::isVisibleContentLine)) {
            kept.remove(0);
        }
        String text = String.join("\n", kept).trim();
        return StringUtils.hasText(text) ? text : override.trim();
    }

    static String bindAtMentions(String text, Map<String, Integer> pictures) {
        if (!StringUtils.hasText(text) || pictures == null || pictures.isEmpty()) {
            return text == null ? "" : text;
        }
        List<String> names = new ArrayList<>(pictures.keySet());
        names.sort(Comparator.comparingInt(String::length).reversed());
        StringBuilder out = new StringBuilder();
        for (TextSpan span : splitSpans(text)) {
            out.append(span.dialogue() ? span.text() : replaceAtNames(span.text(), names, pictures));
        }
        return out.toString();
    }

    static String markAtNames(String text, List<String> names) {
        if (!StringUtils.hasText(text) || names == null || names.isEmpty()) {
            return text;
        }
        List<String> sorted = normalizeNames(names);
        String source = text;
        StringBuilder out = new StringBuilder();
        int index = 0;
        while (index < source.length()) {
            if (source.charAt(index) == '@') {
                String kept = longestNameAt(source, index + 1, sorted);
                if (kept != null) {
                    out.append('@').append(kept);
                    index += 1 + kept.length();
                    continue;
                }
                int end = index + 1;
                while (end < source.length() && !isAtDelimiter(source.charAt(end))) {
                    end++;
                }
                out.append(source, index + 1, end);
                index = end;
                continue;
            }
            String matched = longestNameAt(source, index, sorted);
            if (matched != null) {
                out.append('@').append(matched);
                index += matched.length();
                continue;
            }
            out.append(source.charAt(index));
            index++;
        }
        return out.toString();
    }

    private static void appendVisibleLine(StringBuilder body, String label, String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        if (!body.isEmpty()) {
            body.append('\n');
        }
        body.append(label).append('：').append(value.trim());
    }

    /** 每条可见正文都有环境声。有声音时写在这一行，并写明不要配乐；空着也留下这句约束。 */
    private static void appendVisibleAmbiance(StringBuilder body, String ambiance, List<String> names) {
        String sound = blank(markAtNames(ambiance, names));
        String line;
        if (!StringUtils.hasText(sound)) {
            line = VISIBLE_NO_SCORE;
        } else if (sound.contains("不要配乐")) {
            line = sound;
        } else {
            line = stripEndPunct(sound) + "。" + VISIBLE_NO_SCORE;
        }
        appendVisibleLine(body, "环境声", line);
    }

    private static void appendVisibleClause(StringBuilder block, String label, String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        if (!block.isEmpty()) {
            block.append(' ');
        }
        block.append(label).append('：').append(value.trim());
        if (!value.trim().endsWith("。") && !value.trim().endsWith(".")) {
            block.append('。');
        }
    }

    private static String plainDialogue(String dialogue) {
        if (!StringUtils.hasText(dialogue)) {
            return null;
        }
        return stripAtPrefix(dialogue.trim());
    }

    /**
     * 场景单独一行。地点在引用名单里、且描述里没有这个名字时，补在行首并加 {@code @}。
     */
    private static String sceneLine(String scene, String location, List<String> names) {
        String loc = blank(location);
        String desc = blank(scene);
        boolean locImaged = StringUtils.hasText(loc) && names != null && names.contains(loc);
        if (!StringUtils.hasText(loc) && !StringUtils.hasText(desc)) {
            return null;
        }
        if (!StringUtils.hasText(desc)) {
            return locImaged ? "@" + loc : loc;
        }
        String marked = markAtNames(desc, names);
        if (!StringUtils.hasText(loc) || desc.contains(loc)) {
            return marked;
        }
        return (locImaged ? "@" + loc : loc) + "。" + marked;
    }

    private static String markedName(String name, List<String> names) {
        return markAtNames(name, names);
    }

    private static String cameraHeader(String camera, String continuity) {
        String cam = blank(camera);
        String cont = blank(continuity);
        StringBuilder header = new StringBuilder();
        if (StringUtils.hasText(cam)) {
            header.append(stripEndPunct(cam));
        }
        if (StringUtils.hasText(cont)) {
            if (!header.isEmpty()) {
                header.append('。');
            }
            if (!cont.startsWith("接")) {
                header.append('接');
            }
            header.append(stripEndPunct(cont));
        }
        if (!header.isEmpty() && !header.toString().endsWith("。") && !header.toString().endsWith(".")) {
            header.append('。');
        }
        return header.toString();
    }

    /**
     * 按动作分句顺序，把同一说话人的下一句未用台词接成「动作，说：台词」。
     * 同一角色用逗号连着的动作留在同一句里；只有换了说话人才切开。
     * 对不上动作的台词只补「@名字 说：」，不编造动作。旁白和画外音单独成行。
     */
    private static List<String> performanceLines(String action, String dialogue, List<String> names, List<String> cast) {
        List<SpokenUtterance> pending = new ArrayList<>(spokenUtterances(dialogue));
        boolean[] used = new boolean[pending.size()];
        List<String> speakers = speakerCandidates(cast, pending);
        List<String> lines = new ArrayList<>();
        for (String clause : actionClauses(action, speakers)) {
            String speaker = leadingSpeaker(clause, speakers);
            int spokenIndex = takeSpoken(pending, used, speaker);
            String marked = withSpeakerGap(markAtNames(trimEdgePunct(clause), names), speaker);
            if (spokenIndex < 0) {
                lines.add(ensureSentence(marked));
                continue;
            }
            SpokenUtterance spoken = pending.get(spokenIndex);
            lines.add(wovenSpeech(marked, spoken));
        }
        for (int i = 0; i < pending.size(); i++) {
            if (!used[i]) {
                lines.add(looseSpeech(pending.get(i), names));
            }
        }
        return lines;
    }

    private static List<String> speakerCandidates(List<String> cast, List<SpokenUtterance> pending) {
        List<String> speakers = new ArrayList<>();
        if (cast != null) {
            for (String name : cast) {
                if (StringUtils.hasText(name) && !speakers.contains(name.trim())) {
                    speakers.add(name.trim());
                }
            }
        }
        for (SpokenUtterance spoken : pending) {
            if (spoken == null || spoken.narration() || spoken.voiceOver() || !StringUtils.hasText(spoken.speaker())) {
                continue;
            }
            if (!speakers.contains(spoken.speaker())) {
                speakers.add(spoken.speaker());
            }
        }
        speakers.sort(Comparator.comparingInt(String::length).reversed());
        return speakers;
    }

    /**
     * 先按句号、分号和换行成句，再只在逗号、顿号后面换成另一个说话人时切开。
     * 「洛白按下开关，庶流扩散」仍是洛白的一句；「洛白按下开关，夏安转身」分成两句。
     */
    private static List<String> actionClauses(String action, List<String> speakers) {
        List<String> clauses = new ArrayList<>();
        if (!StringUtils.hasText(action)) {
            return clauses;
        }
        for (String sentence : action.split("[。；\\n\\r]+")) {
            if (!StringUtils.hasText(sentence)) {
                continue;
            }
            clauses.addAll(splitSpeakerTurns(sentence.trim(), speakers));
        }
        return clauses;
    }

    private static List<String> splitSpeakerTurns(String sentence, List<String> speakers) {
        List<String> groups = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String currentSpeaker = null;
        for (String raw : sentence.split("[，、]+")) {
            if (!StringUtils.hasText(raw)) {
                continue;
            }
            String part = raw.trim();
            String speaker = leadingSpeaker(part, speakers);
            if (current.isEmpty()) {
                current.append(part);
                currentSpeaker = speaker;
                continue;
            }
            if (speaker != null && !speaker.equals(currentSpeaker)) {
                groups.add(current.toString());
                current = new StringBuilder(part);
                currentSpeaker = speaker;
                continue;
            }
            current.append('，').append(part);
        }
        if (!current.isEmpty()) {
            groups.add(current.toString());
        }
        return groups;
    }

    private static String leadingSpeaker(String clause, List<String> speakers) {
        String text = clause == null ? "" : clause.trim();
        if (text.startsWith("@")) {
            text = text.substring(1).trim();
        }
        for (String speaker : speakers) {
            if (text.startsWith(speaker)) {
                return speaker;
            }
        }
        return null;
    }

    private static int takeSpoken(List<SpokenUtterance> pending, boolean[] used, String speaker) {
        if (!StringUtils.hasText(speaker)) {
            return -1;
        }
        for (int i = 0; i < pending.size(); i++) {
            SpokenUtterance spoken = pending.get(i);
            if (used[i] || spoken.narration() || spoken.voiceOver()) {
                continue;
            }
            if (speaker.equals(spoken.speaker())) {
                used[i] = true;
                return i;
            }
        }
        return -1;
    }

    private static String withSpeakerGap(String marked, String speaker) {
        if (!StringUtils.hasText(marked) || !StringUtils.hasText(speaker)) {
            return marked;
        }
        String prefix = "@" + speaker;
        if (!marked.startsWith(prefix) || marked.length() == prefix.length()) {
            return marked;
        }
        char next = marked.charAt(prefix.length());
        if (Character.isWhitespace(next) || isAtDelimiter(next)) {
            return marked;
        }
        return prefix + " " + marked.substring(prefix.length());
    }

    private static final String[] SPEECH_VERBS = {
            "回应", "低语", "呢喃", "吩咐", "开口", "喊", "叫", "问", "道", "说", "答", "喝", "吼", "嚷", "念"
    };

    private static final Pattern EMOTION_ADVERB = Pattern.compile("(高兴|愤怒|悲伤|恐惧|惊讶|厌恶|紧张)地");

    private static String wovenSpeech(String marked, SpokenUtterance spoken) {
        String body = stripEndPunct(marked).trim();
        String words = spoken.words();
        String verb = speechVerbEnding(body);
        if (verb != null) {
            return withEmotionVerb(body, spoken.emotion(), verb) + "：“" + words + "”";
        }
        if (hasEmotionAdverb(body)) {
            return body + "：“" + words + "”";
        }
        String glue = body.equals("@" + spoken.speaker()) ? " " : "，";
        if (StringUtils.hasText(spoken.emotion())) {
            return body + glue + spoken.emotion() + "地说：“" + words + "”";
        }
        return body + glue + "说：“" + words + "”";
    }

    private static String looseSpeech(SpokenUtterance spoken, List<String> names) {
        if (spoken.narration()) {
            return "旁白：" + spoken.words();
        }
        if (spoken.voiceOver()) {
            return markedName(spoken.speaker(), names) + "（画外音）：" + spoken.words();
        }
        String who = citedName(spoken.speaker(), names);
        if (StringUtils.hasText(spoken.emotion())) {
            return who + " " + spoken.emotion() + "地说：“" + spoken.words() + "”";
        }
        return who + " 说：“" + spoken.words() + "”";
    }

    private static String speechVerbEnding(String body) {
        if (!StringUtils.hasText(body)) {
            return null;
        }
        for (String verb : SPEECH_VERBS) {
            if (body.endsWith(verb)) {
                return verb;
            }
        }
        return null;
    }

    private static boolean hasEmotionAdverb(String text) {
        return StringUtils.hasText(text) && EMOTION_ADVERB.matcher(text).find();
    }

    /** 动作已经以问、道、喊、说收尾时，把闭集情绪贴在这个动作前面，不再另加一个「说」。 */
    private static String withEmotionVerb(String body, String emotion, String verb) {
        if (!StringUtils.hasText(emotion) || hasEmotionAdverb(body)) {
            return body;
        }
        int at = body.length() - verb.length();
        return body.substring(0, at) + emotion + "地" + verb;
    }

    /** 资产名还在引用名单里才加 @，否则保留普通名字。 */
    private static String citedName(String name, List<String> names) {
        String trimmed = blank(name);
        if (!StringUtils.hasText(trimmed)) {
            return trimmed;
        }
        if (names != null && names.contains(trimmed)) {
            return "@" + trimmed;
        }
        return trimmed;
    }

    private static String trimEdgePunct(String text) {
        String value = blank(text);
        int start = 0;
        int end = value.length();
        while (start < end && isClausePunct(value.charAt(start))) {
            start++;
        }
        while (end > start && isClausePunct(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isClausePunct(char value) {
        return value == '，' || value == '。' || value == '；' || value == '、'
                || value == ',' || value == '.' || value == ';';
    }

    private static String stripEndPunct(String text) {
        String value = blank(text);
        while (!value.isEmpty()) {
            char last = value.charAt(value.length() - 1);
            if (last == '。' || last == '.' || last == '！' || last == '？' || last == '!' || last == '?') {
                value = value.substring(0, value.length() - 1).trim();
                continue;
            }
            break;
        }
        return value;
    }

    private static String ensureSentence(String text) {
        String value = blank(text);
        if (!StringUtils.hasText(value)) {
            return value;
        }
        if (value.endsWith("。") || value.endsWith(".") || value.endsWith("！") || value.endsWith("？")) {
            return value;
        }
        return value + "。";
    }

    private static String writeSayDialogue(String visible, String dialogue) {
        List<String> lines = dialogueLines(dialogue);
        List<SaySlot> slots = saySlots(visible);
        StringBuilder out = new StringBuilder();
        int cursor = 0;
        for (int i = 0; i < slots.size(); i++) {
            SaySlot slot = slots.get(i);
            String words = i < lines.size() ? spokenWords(lines.get(i)) : "";
            if (StringUtils.hasText(words)) {
                out.append(visible, cursor, slot.contentStart());
                out.append(words);
            } else {
                int cut = slot.markerStart();
                if (cut > cursor && visible.charAt(cut - 1) == '，') {
                    cut--;
                }
                out.append(visible, cursor, cut);
            }
            cursor = StringUtils.hasText(words) ? slot.contentEnd() : slot.resume();
        }
        if (cursor < visible.length()) {
            out.append(visible.substring(cursor));
        }
        if (lines.size() > slots.size()) {
            out.append(formatExtraSayLines(lines.subList(slots.size(), lines.size())));
        }
        return out.toString().trim();
    }

    private static String formatExtraSayLines(List<String> lines) {
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            String words = spokenWords(line);
            if (!StringUtils.hasText(words)) {
                continue;
            }
            String speaker = speakerPrefix(line);
            out.append('\n');
            if (speaker.startsWith("旁白")) {
                out.append("旁白：").append(words);
            } else if (speaker.contains("画外音")) {
                String name = speaker.replace("（画外音）", "").replace("(画外音)", "").trim();
                if (name.startsWith("@")) {
                    name = name.substring(1).trim();
                }
                out.append('@').append(name).append("（画外音）：").append(words);
            } else if (StringUtils.hasText(speaker)) {
                SpeakerEmotion parsed = parseSpeakerEmotion(speaker);
                String emotion = DramaDialogueSpeakers.sanitizeEmotion(parsed.emotion());
                String name = StringUtils.hasText(parsed.speaker()) ? parsed.speaker() : speaker;
                out.append('@').append(name);
                if (StringUtils.hasText(emotion)) {
                    out.append(' ').append(emotion).append("地说：");
                } else {
                    out.append(" 说：");
                }
                out.append(words);
            } else {
                out.append("说：").append(words);
            }
        }
        return out.toString();
    }

    private static String spokenWords(String line) {
        if (!StringUtils.hasText(line)) {
            return "";
        }
        int sep = speakerSeparator(line);
        if (sep > 0 && sep <= 32) {
            return line.substring(sep + 1).trim();
        }
        return line.trim();
    }

    private static String speakerPrefix(String line) {
        if (!StringUtils.hasText(line)) {
            return "";
        }
        int sep = speakerSeparator(line);
        if (sep > 0 && sep <= 32) {
            String speaker = stripAtPrefix(line.substring(0, sep)).trim();
            return speaker;
        }
        return "";
    }

    private static List<SaySlot> saySlots(String text) {
        List<SaySlot> slots = new ArrayList<>();
        for (SpeechHit hit : absoluteSpeechHits(text)) {
            slots.add(new SaySlot(hit.markerStart(), hit.contentStart(), hit.contentEnd(), hit.resume()));
        }
        return slots;
    }

    /** 台词标签整段已是口播，不再从里面拆「说：」。下标相对整段正文。 */
    private static List<SpeechHit> absoluteSpeechHits(String text) {
        List<SpeechHit> hits = new ArrayList<>();
        if (!StringUtils.hasText(text)) {
            return hits;
        }
        int offset = 0;
        for (TextSpan span : splitLabelSpans(text)) {
            if (!span.dialogue()) {
                for (SpeechHit hit : findSpeechHits(span.text())) {
                    hits.add(shiftSpeechHit(hit, offset));
                }
            }
            offset += span.text() == null ? 0 : span.text().length();
        }
        return hits;
    }

    private static SpeechHit shiftSpeechHit(SpeechHit hit, int offset) {
        return new SpeechHit(
                hit.markerStart() + offset,
                hit.quoteOpen() < 0 ? -1 : hit.quoteOpen() + offset,
                hit.contentStart() + offset,
                hit.contentEnd() + offset,
                hit.resume() + offset,
                hit.needsQuotes(),
                hit.offScreen());
    }

    private static boolean isLegacyDialogueSpan(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        return text.trim().startsWith("台词：") || text.trim().startsWith("台词:");
    }

    /** 闭引号紧跟着的句号、叹号仍算这句台词的标点，不留在引号外面丢掉。 */
    private static String punctuationAfterClosingQuote(List<TextSpan> spans, int dialogueIndex) {
        if (dialogueIndex + 1 >= spans.size()) {
            return "";
        }
        TextSpan next = spans.get(dialogueIndex + 1);
        if (next == null || next.dialogue() || !StringUtils.hasText(next.text())) {
            return "";
        }
        String text = next.text();
        char open = text.charAt(0);
        if (open != '”' && open != '"') {
            return "";
        }
        int end = 1;
        while (end < text.length() && isTrailingSpeechPunct(text.charAt(end))) {
            end++;
        }
        return text.substring(1, end);
    }

    private static boolean isTrailingSpeechPunct(char value) {
        return value == '。' || value == '！' || value == '？' || value == '…'
                || value == '.' || value == '!' || value == '?' || value == '，' || value == ',';
    }

    private static String previousNarrative(List<TextSpan> spans, int index) {
        for (int i = index - 1; i >= 0; i--) {
            if (!spans.get(i).dialogue()) {
                return spans.get(i).text();
            }
        }
        return "";
    }

    private static String speakerFromNarrative(String narrative) {
        if (!StringUtils.hasText(narrative)) {
            return "";
        }
        SpeechHit hit = lastSpeechHit(narrative);
        if (hit == null) {
            return nearestSpeakerAt(narrative);
        }
        if (hit.offScreen()) {
            String marker = narrative.substring(hit.markerStart(), Math.min(narrative.length(), hit.markerStart() + 6));
            if (marker.startsWith("旁白")) {
                return "旁白";
            }
            String name = nearestSpeakerAt(narrative.substring(0, hit.markerStart()));
            return StringUtils.hasText(name) ? name + "（画外音）" : "";
        }
        return nearestSpeakerAt(narrative.substring(0, hit.markerStart()));
    }

    /** 冒号前最近一个带 {@code @} 的分句里，取开头那个名字。说话动作本身可以没有 {@code @}。 */
    private static String nearestSpeakerAt(String before) {
        if (!StringUtils.hasText(before)) {
            return "";
        }
        String clause = before.replaceAll("[，。；、\\s]+$", "");
        int end = clause.length();
        while (end > 0) {
            int cut = Math.max(clause.lastIndexOf('\n', end - 1),
                    Math.max(clause.lastIndexOf('，', end - 1),
                            Math.max(clause.lastIndexOf('。', end - 1), clause.lastIndexOf('；', end - 1))));
            String name = atName(clause.substring(cut + 1, end).trim());
            if (StringUtils.hasText(name)) {
                return name;
            }
            if (cut < 0) {
                break;
            }
            end = cut;
        }
        return "";
    }

    /** 冒号前是「愤怒地说」「愤怒地问」时，把闭集情绪写回台词字段。 */
    private static String emotionAdverbBefore(String narrative) {
        SpeechHit hit = lastSpeechHit(narrative);
        if (hit == null || hit.offScreen() || !StringUtils.hasText(narrative)) {
            return null;
        }
        String before = narrative.substring(0, hit.markerStart()).replaceAll("[\\s，。；、]+$", "");
        Matcher matcher = EMOTION_ADVERB.matcher(before);
        int at = -1;
        String emotion = null;
        while (matcher.find()) {
            at = matcher.start();
            emotion = matcher.group(1);
        }
        if (emotion == null || at < 0) {
            return null;
        }
        String after = before.substring(at + emotion.length() + 1);
        if (!after.isEmpty() && speechVerbEnding(after) == null) {
            return null;
        }
        if (!after.isEmpty() && !after.equals(speechVerbEnding(after))) {
            return null;
        }
        return DramaDialogueSpeakers.sanitizeEmotion(emotion);
    }

    /**
     * 角色口播用中文引号标出台词。旁白、画外音和旧「台词：」不加引号。
     * 已经包过一层的不再套。没有引号的旧「说：」仍补上引号。
     */
    public static String quoteSpokenLines(String text) {
        return rewriteSaySpeech(text, true);
    }

    /** 去掉口播外层中文引号，供指纹和台词框使用。 */
    private static String unquoteSpokenLines(String text) {
        return rewriteSaySpeech(text, false);
    }

    private static String rewriteSaySpeech(String text, boolean quote) {
        if (!StringUtils.hasText(text)) {
            return text == null ? "" : text;
        }
        List<SpeechHit> hits = absoluteSpeechHits(text);
        if (hits.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        int cursor = 0;
        for (SpeechHit hit : hits) {
            String speech = text.substring(hit.contentStart(), Math.min(hit.contentEnd(), text.length()));
            if (quote) {
                out.append(text, cursor, hit.contentStart());
                out.append(hit.needsQuotes() ? quoteSpeech(speech) : speech);
                cursor = hit.contentEnd();
            } else if (hit.quoteOpen() >= 0) {
                out.append(text, cursor, hit.quoteOpen());
                out.append(speech);
                cursor = hit.resume();
            } else {
                out.append(text, cursor, hit.contentStart());
                out.append(unquoteSpeech(speech));
                cursor = hit.contentEnd();
            }
        }
        if (cursor < text.length()) {
            out.append(text.substring(cursor));
        }
        return out.toString();
    }

    private static boolean precededBySay(String narrative) {
        SpeechHit hit = lastSpeechHit(narrative);
        return hit != null && !hit.offScreen();
    }

    private static String quoteSpeech(String speech) {
        if (!StringUtils.hasText(speech)) {
            return speech == null ? "" : speech;
        }
        int start = 0;
        int end = speech.length();
        while (start < end && Character.isWhitespace(speech.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(speech.charAt(end - 1))) {
            end--;
        }
        String core = speech.substring(start, end);
        if (wrappingQuoteClose(core) >= 0) {
            return speech;
        }
        return speech.substring(0, start) + "“" + core + "”" + speech.substring(end);
    }

    private static String unquoteSpeech(String speech) {
        if (!StringUtils.hasText(speech)) {
            return speech == null ? "" : speech;
        }
        int start = 0;
        int end = speech.length();
        while (start < end && Character.isWhitespace(speech.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(speech.charAt(end - 1))) {
            end--;
        }
        String stripped = stripWrappingQuotes(speech.substring(start, end));
        return speech.substring(0, start) + stripped + speech.substring(end);
    }

    private static String stripWrappingQuotes(String words) {
        if (!StringUtils.hasText(words)) {
            return words == null ? "" : words;
        }
        String trimmed = words.trim();
        int close = wrappingQuoteClose(trimmed);
        if (close < 0) {
            return words;
        }
        String inner = trimmed.substring(1, close).trim();
        String trailing = trimmed.substring(close + 1).trim();
        if (!StringUtils.hasText(inner)) {
            return trailing;
        }
        return StringUtils.hasText(trailing) ? inner + trailing : inner;
    }

    /** 以“开头，且”之后只剩句末标点时，才是包住整句台词的外层引号。 */
    private static int wrappingQuoteClose(String trimmed) {
        if (!StringUtils.hasText(trimmed) || !trimmed.startsWith("“")) {
            return -1;
        }
        int close = trimmed.lastIndexOf('”');
        if (close <= 0) {
            return -1;
        }
        String trailing = trimmed.substring(close + 1).trim();
        for (int i = 0; i < trailing.length(); i++) {
            char c = trailing.charAt(i);
            if (c != '。' && c != '！' && c != '？' && c != '…' && c != '.' && c != '!' && c != '?'
                    && c != '，' && c != ',') {
                return -1;
            }
        }
        return close;
    }

    private static String atName(String tail) {
        if (!StringUtils.hasText(tail)) {
            return "";
        }
        int at = tail.indexOf('@');
        if (at < 0) {
            return "";
        }
        int end = at + 1;
        while (end < tail.length() && !isAtDelimiter(tail.charAt(end))) {
            end++;
        }
        return end > at + 1 ? tail.substring(at + 1, end) : "";
    }

    private static List<String> normalizeNames(List<String> names) {
        List<String> sorted = new ArrayList<>();
        if (names == null) {
            return sorted;
        }
        for (String name : names) {
            if (!StringUtils.hasText(name)) {
                continue;
            }
            String trimmed = name.trim();
            if (!sorted.contains(trimmed)) {
                sorted.add(trimmed);
            }
        }
        sorted.sort(Comparator.comparingInt(String::length).reversed());
        return sorted;
    }

    private static void collectAtNames(String text, List<String> known, List<String> into) {
        if (!StringUtils.hasText(text)) {
            return;
        }
        int index = 0;
        while (index < text.length()) {
            if (text.charAt(index) != '@') {
                index++;
                continue;
            }
            String matched = longestNameAt(text, index + 1, known);
            if (matched == null) {
                int end = index + 1;
                while (end < text.length() && !isAtDelimiter(text.charAt(end))) {
                    end++;
                }
                matched = end > index + 1 ? text.substring(index + 1, end) : null;
            }
            if (!StringUtils.hasText(matched)) {
                index++;
                continue;
            }
            if (!into.contains(matched)) {
                into.add(matched);
            }
            index += 1 + matched.length();
        }
    }

    private static String replaceAtNames(String text, List<String> names, Map<String, Integer> pictures) {
        if (!StringUtils.hasText(text)) {
            return text == null ? "" : text;
        }
        StringBuilder out = new StringBuilder();
        int index = 0;
        while (index < text.length()) {
            if (text.charAt(index) != '@') {
                out.append(text.charAt(index));
                index++;
                continue;
            }
            String matched = longestNameAt(text, index + 1, names);
            Integer picture = matched == null ? null : pictures.get(matched);
            if (matched != null && picture != null) {
                out.append(matched).append("（<Picture ").append(picture).append(">）");
                index += 1 + matched.length();
                continue;
            }
            out.append('@');
            index++;
        }
        return out.toString();
    }

    private static String longestNameAt(String text, int offset, List<String> names) {
        if (names == null || offset < 0 || offset > text.length()) {
            return null;
        }
        String matched = null;
        for (String name : names) {
            if (!StringUtils.hasText(name) || offset + name.length() > text.length()) {
                continue;
            }
            if (text.regionMatches(offset, name, 0, name.length())
                    && (matched == null || name.length() > matched.length())) {
                matched = name;
            }
        }
        return matched;
    }

    private static boolean isAtDelimiter(char value) {
        return Character.isWhitespace(value)
                || value == '，' || value == '。' || value == '；' || value == '、'
                || value == '：' || value == ':' || value == '（' || value == '）'
                || value == '(' || value == ')' || value == '@' || value == '<'
                || value == '>' || value == '！' || value == '？' || value == ','
                || value == '.' || value == ';' || value == '!' || value == '?';
    }

    private static String stripAtPrefix(String text) {
        if (!StringUtils.hasText(text)) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (current == '@' && i + 1 < text.length() && !isAtDelimiter(text.charAt(i + 1))) {
                continue;
            }
            out.append(current);
        }
        return out.toString();
    }

    private static String dialogueBody(String labeled) {
        if (!StringUtils.hasText(labeled)) {
            return "";
        }
        Matcher matcher = Pattern.compile("^台词\\s*[:：]\\s*").matcher(labeled.trim());
        String body = matcher.find() ? labeled.trim().substring(matcher.end()) : labeled.trim();
        return stripAtPrefix(body).trim();
    }

    private static List<String> dialogueLines(String dialogue) {
        List<String> lines = new ArrayList<>();
        if (!StringUtils.hasText(dialogue)) {
            return lines;
        }
        for (String raw : dialogue.split("\\r?\\n")) {
            String line = stripAtPrefix(raw == null ? "" : raw).trim();
            if (StringUtils.hasText(line)) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static String appendDialogueClauses(String visible, List<LabelHit> labels, List<String> lines) {
        if (lines.isEmpty()) {
            return visible;
        }
        List<LabelHit> shots = new ArrayList<>();
        for (LabelHit label : labels) {
            if (label.name().startsWith("镜头")) {
                shots.add(label);
            }
        }
        if (shots.isEmpty()) {
            return visible;
        }
        StringBuilder out = new StringBuilder();
        int cursor = 0;
        for (int i = 0; i < shots.size(); i++) {
            int end = i + 1 < shots.size() ? shots.get(i + 1).start() : visible.length();
            out.append(visible, cursor, end);
            if (i < lines.size()) {
                if (out.length() > 0 && out.charAt(out.length() - 1) != '\n' && out.charAt(out.length() - 1) != ' ') {
                    out.append(' ');
                }
                out.append("台词：").append(lines.get(i));
            }
            cursor = end;
        }
        if (lines.size() > shots.size()) {
            out.append('\n').append("台词：").append(String.join("\n", lines.subList(shots.size(), lines.size())));
        }
        return out.toString().trim();
    }

    private static int nextLabelAfter(List<LabelHit> labels, int index, int fallback) {
        return index + 1 < labels.size() ? labels.get(index + 1).start() : fallback;
    }

    private static boolean startsBlock(String line) {
        return line.startsWith("Active References")
                || line.startsWith("Voice References")
                || line.startsWith("Identities");
    }

    private static boolean isVisibleContentLine(String line) {
        return line.startsWith("Scene:")
                || line.startsWith("Camera:")
                || line.startsWith("Action:")
                || line.startsWith("Dialogue:")
                || line.startsWith("Ambiance:")
                || line.startsWith("站位")
                || line.startsWith("镜头")
                || line.startsWith("场景")
                || line.startsWith("运镜")
                || line.startsWith("动作")
                || line.startsWith("环境声")
                || line.startsWith("景别")
                || line.startsWith("接续")
                || line.startsWith("台词");
    }

    private static boolean isTemplateLine(String line) {
        if (line.matches("^(<Picture \\d+>\\s*)+$")) {
            return true;
        }
        if (line.startsWith("参考图：")
                || line.startsWith("Must")
                || line.startsWith("Duration:")
                || line.startsWith("Continuity in")
                || line.startsWith("Continuity out")
                || line.startsWith("衔接：")
                || line.startsWith("对白执行")
                || line.startsWith("对白表演")
                || line.startsWith("出场：")
                || line.startsWith("本镜出场")
                || line.startsWith("未出图角色")
                || line.contains("本镜恰好")
                || line.contains("某一镜头没点名")
                || line.startsWith("景别：对白和问责")
                || line.startsWith("<Picture ")
                || line.startsWith("<Audio ")) {
            return true;
        }
        return false;
    }

    private record TextSpan(boolean dialogue, String text) {
    }

    private record LabelHit(int start, int end, String name) {
    }

    private record SaySlot(int markerStart, int contentStart, int contentEnd, int resume) {
    }

    /**
     * 一句口播。{@code contentStart}/{@code contentEnd} 是引号内或旧无引号「说：」后的正文。
     * {@code quoteOpen} 为开引号下标；没有引号时为 -1。{@code resume} 停在闭引号之后，便于同一行继续找下一句。
     */
    private record SpeechHit(
            int markerStart,
            int quoteOpen,
            int contentStart,
            int contentEnd,
            int resume,
            boolean needsQuotes,
            boolean offScreen) {
    }

    private static List<TextSpan> splitSpans(String text) {
        List<TextSpan> spans = new ArrayList<>();
        for (TextSpan span : splitLabelSpans(text)) {
            if (span.dialogue()) {
                spans.add(span);
                continue;
            }
            spans.addAll(splitSaySpans(span.text()));
        }
        return spans;
    }

    private static List<TextSpan> splitLabelSpans(String text) {
        List<TextSpan> spans = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return spans;
        }
        Matcher matcher = VISIBLE_LABEL.matcher(text);
        List<LabelHit> labels = new ArrayList<>();
        while (matcher.find()) {
            labels.add(new LabelHit(matcher.start(), matcher.end(), matcher.group(1)));
        }
        if (labels.isEmpty()) {
            spans.add(new TextSpan(false, text));
            return spans;
        }
        int cursor = 0;
        for (int i = 0; i < labels.size(); i++) {
            LabelHit label = labels.get(i);
            if (label.start() > cursor) {
                spans.add(new TextSpan(false, text.substring(cursor, label.start())));
            }
            int contentEnd = i + 1 < labels.size() ? labels.get(i + 1).start() : text.length();
            boolean dialogue = "台词".equals(label.name());
            spans.add(new TextSpan(dialogue, text.substring(label.start(), contentEnd)));
            cursor = contentEnd;
        }
        if (cursor < text.length()) {
            spans.add(new TextSpan(false, text.substring(cursor)));
        }
        return spans;
    }

    private static List<TextSpan> splitSaySpans(String text) {
        List<TextSpan> spans = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return spans;
        }
        List<SpeechHit> hits = findSpeechHits(text);
        if (hits.isEmpty()) {
            spans.add(new TextSpan(false, text));
            return spans;
        }
        int cursor = 0;
        for (SpeechHit hit : hits) {
            if (hit.contentStart() > cursor) {
                spans.add(new TextSpan(false, text.substring(cursor, hit.contentStart())));
            }
            if (hit.contentEnd() > hit.contentStart()) {
                spans.add(new TextSpan(true, text.substring(hit.contentStart(), hit.contentEnd())));
            }
            cursor = hit.contentEnd();
        }
        if (cursor < text.length()) {
            spans.add(new TextSpan(false, text.substring(cursor)));
        }
        return spans;
    }

    /**
     * 冒号后只允许空白、紧接着“或"时，引号内是台词。引号没关上就收到本行末尾。
     * 旧稿继续认没有引号的「说：」，以及「旁白：」「（画外音）：」。
     * 场景、镜头、站位这类冒号后面没有引号，不当台词。
     */
    private static List<SpeechHit> findSpeechHits(String text) {
        List<SpeechHit> hits = new ArrayList<>();
        if (!StringUtils.hasText(text)) {
            return hits;
        }
        int from = 0;
        while (from < text.length()) {
            SpeechHit hit = nextSpeechHit(text, from);
            if (hit == null) {
                break;
            }
            hits.add(hit);
            int next = Math.max(hit.resume(), hit.markerStart() + 1);
            if (next <= from) {
                break;
            }
            from = next;
        }
        return hits;
    }

    private static SpeechHit lastSpeechHit(String text) {
        List<SpeechHit> hits = findSpeechHits(text);
        return hits.isEmpty() ? null : hits.get(hits.size() - 1);
    }

    private static SpeechHit nextSpeechHit(String text, int from) {
        Matcher legacy = LEGACY_SPEECH.matcher(text);
        boolean foundLegacy = legacy.find(from);
        int colonAt = indexOfColonBeforeQuote(text, from);
        if (foundLegacy && (colonAt < 0 || legacy.start() <= colonAt)) {
            return hitFromLegacy(text, legacy.start(), legacy.end(), legacy.group());
        }
        if (colonAt >= 0) {
            return hitFromColonQuote(text, colonAt);
        }
        return null;
    }

    private static SpeechHit hitFromLegacy(String text, int start, int end, String mark) {
        boolean offScreen = mark.startsWith("旁白") || mark.contains("画外音");
        int limit = lineEnd(text, end);
        if (offScreen) {
            return new SpeechHit(start, -1, end, limit, limit, false, true);
        }
        int openAt = end;
        while (openAt < limit && (text.charAt(openAt) == ' ' || text.charAt(openAt) == '\t')) {
            openAt++;
        }
        if (openAt < limit && (text.charAt(openAt) == '“' || text.charAt(openAt) == '"')) {
            return quotedHit(text, start, openAt, limit, false);
        }
        return new SpeechHit(start, -1, end, limit, limit, true, false);
    }

    private static SpeechHit hitFromColonQuote(String text, int colonAt) {
        int openAt = colonAt + 1;
        int limit = lineEnd(text, colonAt);
        while (openAt < limit && (text.charAt(openAt) == ' ' || text.charAt(openAt) == '\t')) {
            openAt++;
        }
        return quotedHit(text, colonAt, openAt, limit, false);
    }

    private static SpeechHit quotedHit(String text, int markerStart, int openAt, int limit, boolean offScreen) {
        char open = text.charAt(openAt);
        char closeChar = open == '“' ? '”' : '"';
        int close = text.indexOf(closeChar, openAt + 1);
        if (close < 0 || close > limit) {
            return new SpeechHit(markerStart, openAt, openAt + 1, limit, limit, false, offScreen);
        }
        return new SpeechHit(markerStart, openAt, openAt + 1, close, close + 1, false, offScreen);
    }

    private static int indexOfColonBeforeQuote(String text, int from) {
        for (int i = Math.max(from, 0); i < text.length(); i++) {
            char value = text.charAt(i);
            if (value != '：' && value != ':') {
                continue;
            }
            int openAt = i + 1;
            while (openAt < text.length() && (text.charAt(openAt) == ' ' || text.charAt(openAt) == '\t')) {
                openAt++;
            }
            if (openAt < text.length() && text.charAt(openAt) != '\n'
                    && (text.charAt(openAt) == '“' || text.charAt(openAt) == '"')) {
                return i;
            }
        }
        return -1;
    }

    private static int lineEnd(String text, int from) {
        int lineEnd = text.indexOf('\n', from);
        return lineEnd < 0 ? text.length() : lineEnd;
    }
}
