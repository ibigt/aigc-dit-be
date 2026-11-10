package cn.aigc.dit.server.drama;

import cn.aigc.dit.server.drama.text.ScriptDoc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 地点名去重：同一处地方不能有两个名字。
 *
 * <p>场景资产是按地点逐条抽的：剧本里一处地方既写成「皇后的风仪宫，未央宫」，
 * 又在另一场写成「未央宫」，页面上就会有两张讲同一个地方的场景设定图，
 * 其中一张的名字还是「皇后的风仪宫，未央宫」这种拼接名。
 *
 * <p>语义归一化那一步本该把并列地名合并掉，但模型不一定会照做，这里机械兜一次：
 * 地点名里并列了另一个「本剧本里单独出现过」的地名时，统一用那个单独出现的名字。
 *
 * <p>「紫禁城，风仪宫」这种上位区域写法不受影响 —— 里面的「风仪宫」没有单独出现过，
 * 名字原样保留；真出现了，说明指的就是同一个地方，合掉才对。
 */
public final class DramaPlaceNames {

    /**
     * 并列分隔符：逗号与顿号。
     *
     * <p>斜杠不算并列 —— v1 把「甲/乙」当成两个不同地方，会拆成两张场景图；
     * 间隔号也不算 —— 「未央宫·偏殿」是下属场所，不是一处地方写了两遍。
     */
    private static final String ENUMERATION_SEPARATORS = "[，,、]";

    private DramaPlaceNames() {
    }

    /**
     * 返回地点已合并的剧本副本；原对象不动（归一化步骤的产物要原样留档）。
     */
    public static ScriptDoc mergeDuplicateLocations(ScriptDoc script) {
        if (script == null || script.getScenes() == null || script.getScenes().isEmpty()) {
            return script;
        }
        Set<String> locations = new LinkedHashSet<>();
        for (ScriptDoc.Scene scene : script.getScenes()) {
            if (scene == null) continue;
            String location = trimmed(scene.getLocation());
            if (!location.isEmpty()) {
                locations.add(location);
            }
        }
        if (locations.size() < 2) {
            return script;
        }
        ScriptDoc merged = copyOf(script);
        for (ScriptDoc.Scene scene : merged.getScenes()) {
            if (scene == null) continue;
            String location = trimmed(scene.getLocation());
            if (location.isEmpty()) continue;
            String canonical = canonicalFor(location, locations);
            if (!canonical.equals(location)) {
                scene.setLocation(canonical);
            }
        }
        return merged;
    }

    /**
     * 地点名里并列了哪个「单独出现过」的地名；没有并列项就返回原名。
     */
    public static String canonicalFor(String location, Collection<String> locations) {
        String value = trimmed(location);
        if (value.isEmpty() || locations == null) {
            return location;
        }
        List<String> parts = splitEnumeration(value);
        if (parts.size() < 2) {
            return value;
        }
        for (String part : parts) {
            if (!part.equals(value) && locations.contains(part)) {
                return part;
            }
        }
        return value;
    }

    /** 并列成分：逗号与顿号分隔出来的地名。 */
    public static List<String> splitEnumeration(String value) {
        List<String> parts = new ArrayList<>();
        String text = trimmed(value);
        if (text.isEmpty()) {
            return parts;
        }
        for (String raw : text.split(ENUMERATION_SEPARATORS)) {
            String part = raw.trim();
            if (!part.isEmpty() && !parts.contains(part)) {
                parts.add(part);
            }
        }
        return parts;
    }

    /** 浅拷贝：只复制会被改写的场次地点，其余字段共用。 */
    private static ScriptDoc copyOf(ScriptDoc script) {
        ScriptDoc copy = new ScriptDoc();
        copy.setTitle(script.getTitle());
        copy.setLogline(script.getLogline());
        copy.setStylePrefix(script.getStylePrefix());
        List<ScriptDoc.Scene> scenes = new ArrayList<>();
        for (ScriptDoc.Scene scene : script.getScenes()) {
            if (scene == null) {
                scenes.add(null);
                continue;
            }
            ScriptDoc.Scene sceneCopy = new ScriptDoc.Scene();
            sceneCopy.setIndex(scene.getIndex());
            sceneCopy.setSlug(scene.getSlug());
            sceneCopy.setHeading(scene.getHeading());
            sceneCopy.setCharacters(scene.getCharacters());
            sceneCopy.setProps(scene.getProps());
            sceneCopy.setLocation(scene.getLocation());
            sceneCopy.setTime(scene.getTime());
            sceneCopy.setInterior(scene.getInterior());
            sceneCopy.setAction(scene.getAction());
            sceneCopy.setSound(scene.getSound());
            sceneCopy.setDialogue(scene.getDialogue());
            scenes.add(sceneCopy);
        }
        copy.setScenes(scenes);
        return copy;
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}
