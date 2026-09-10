package cn.aigc.dit.server.drama.text;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 跨集名字守卫：新一集的草稿必须沿用上一集已锁定的资产名，不能给同一个角色发明第二张脸。
 * <p>
 * 规则只有三条：本体名必须逐字相同；「本体名·状态」是合法的新状态资产；
 * 其余「包含已锁定名却不是上述两种形态」的写法一律视为改名，收回到已锁定名上。
 */
public final class EpisodeNameGuard {

    private static final String STATE_SEPARATOR = "·";

    private EpisodeNameGuard() {
    }

    public static ScriptDoc applyTo(ScriptDoc doc, List<String> lockedAssetNames) {
        if (doc == null || doc.getScenes() == null || lockedAssetNames == null || lockedAssetNames.isEmpty()) {
            return doc;
        }
        Set<String> locked = new LinkedHashSet<>(lockedAssetNames);
        for (ScriptDoc.Scene scene : doc.getScenes()) {
            if (scene == null) {
                continue;
            }
            scene.setCharacters(preserve(scene.getCharacters(), locked));
            scene.setProps(preserve(scene.getProps(), locked));
            scene.setLocation(preserveName(scene.getLocation(), locked));
            for (ScriptDoc.Dialogue line : safe(scene.getDialogue())) {
                if (line != null) {
                    line.setSpeaker(preserveName(line.getSpeaker(), locked));
                }
            }
        }
        return doc;
    }

    private static List<String> preserve(List<String> names, Set<String> locked) {
        List<String> preserved = new ArrayList<>();
        for (String name : safe(names)) {
            String clean = preserveName(name, locked);
            if (StringUtils.hasText(clean) && !preserved.contains(clean)) {
                preserved.add(clean);
            }
        }
        return preserved;
    }

    static String preserveName(String raw, Set<String> locked) {
        if (!StringUtils.hasText(raw)) {
            return raw;
        }
        String name = raw.strip();
        if (locked.contains(name)) {
            return name;
        }
        for (String base : locked) {
            if (!StringUtils.hasText(base)) {
                continue;
            }
            // 洛白·入梦 这种状态资产允许新出现，但要归回同一词表判定状态。
            if (name.startsWith(base + STATE_SEPARATOR)) {
                String suffix = name.substring(base.length() + STATE_SEPARATOR.length()).strip();
                return base + STATE_SEPARATOR + (StringUtils.hasText(suffix) ? suffix : "状态");
            }
            if (name.contains(base)) {
                return base;
            }
        }
        return name;
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }
}
