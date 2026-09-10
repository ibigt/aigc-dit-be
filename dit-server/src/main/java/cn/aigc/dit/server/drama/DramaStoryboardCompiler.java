package cn.aigc.dit.server.drama;

import cn.aigc.dit.server.drama.entity.DramaAsset;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 分镜视频 prompt 编译器。每镜自包含：Style Prefix + Active References（Picture 序号与资产顺序一致）
 * + 身份不变量全文 + 场景/动作/台词 + 人数正约束。
 */
public final class DramaStoryboardCompiler {

    public static final String CHAR_PICTURE_HINT = "仅作%s身份与服装，不要继承构图";
    public static final String PROP_PICTURE_HINT = "仅作%s外观与材质，不要继承构图";
    public static final String SCENE_PICTURE_HINT = "可参考陈设与光线，不要改人物身份";

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

    public record ShotContent(
            String scene,
            String narrativeGoal,
            String dialogue,
            String continuityIn,
            String continuityOut,
            int durationSec,
            List<String> characterNames,
            int castCount
    ) {
    }

    public record Result(String prompt, String fingerprint) {
    }

    public static Result compile(String stylePrefix, List<ActiveReference> references, ShotContent shot) {
        List<ActiveReference> refs = references == null ? List.of() : references;
        ShotContent content = shot == null
                ? new ShotContent(null, null, null, null, null, DramaContract.DEFAULT_DURATION_SEC, List.of(), 0)
                : shot;
        String prefix = StringUtils.hasText(stylePrefix)
                ? stylePrefix.trim()
                : DramaStyles.require(DramaStyles.ANIME_COMIC).prefix();

        StringBuilder prompt = new StringBuilder();
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

        appendLine(prompt, "Scene", content.scene());
        appendLine(prompt, "Action", content.narrativeGoal());
        appendLine(prompt, "Dialogue", content.dialogue());
        appendLine(prompt, "Continuity in", content.continuityIn());
        appendLine(prompt, "Continuity out", content.continuityOut());
        prompt.append(castConstraint(content)).append('\n');
        prompt.append("Duration: ").append(content.durationSec()).append(" seconds.");

        return new Result(prompt.toString().trim(), fingerprint(prefix, refs, content));
    }

    public static String fingerprint(String stylePrefix, List<ActiveReference> references, ShotContent shot) {
        StringBuilder raw = new StringBuilder();
        raw.append(blank(stylePrefix)).append('\n');
        if (references != null) {
            for (ActiveReference ref : references) {
                raw.append(ref.assetId()).append(':').append(ref.identityVersion()).append(':')
                        .append(blank(ref.descriptor())).append(':')
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
        }
        return sha256(raw.toString());
    }

    static String fullIdentity(ActiveReference ref) {
        List<String> parts = new ArrayList<>();
        if (StringUtils.hasText(ref.identityInvariants())) {
            parts.add(ref.identityInvariants().trim());
        }
        if (StringUtils.hasText(ref.descriptor())) {
            String descriptor = ref.descriptor().trim();
            if (parts.stream().noneMatch(existing -> existing.equals(descriptor))) {
                parts.add(descriptor);
            }
        }
        return String.join("。", parts);
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

    static String castConstraint(ShotContent shot) {
        List<String> names = shot.characterNames() == null ? List.of() : shot.characterNames().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
        int count = shot.castCount() > 0 ? shot.castCount() : names.size();
        if (count <= 0 && names.isEmpty()) {
            return "Cast: 本镜不要增删人物。";
        }
        String joined = String.join("、", names);
        return "Cast: 本镜恰好 " + count + " 人，不得增删角色。"
                + (joined.isEmpty() ? "" : "出场：" + joined + "。");
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
