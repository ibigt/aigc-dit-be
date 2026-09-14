package cn.aigc.dit.server.drama;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 按台词/动作里首次出现的位置排列人名，避免参考图仍按资产表顺序导致换脸。
 */
public final class DramaNameMentions {

    private DramaNameMentions() {
    }

    public static List<String> mentionedInOrder(List<String> pool, String... texts) {
        String blob = join(texts);
        if (!StringUtils.hasText(blob) || pool == null || pool.isEmpty()) {
            return List.of();
        }
        List<Hit> hits = new ArrayList<>();
        for (String name : pool) {
            if (!StringUtils.hasText(name)) {
                continue;
            }
            int at = firstIndex(blob, name.trim());
            if (at >= 0) {
                hits.add(new Hit(name.trim(), at));
            }
        }
        hits.sort(Comparator.comparingInt(Hit::at).thenComparing(Hit::name));
        List<String> ordered = new ArrayList<>();
        for (Hit hit : hits) {
            if (!ordered.contains(hit.name())) {
                ordered.add(hit.name());
            }
        }
        return ordered;
    }

    static int firstIndex(String blob, String name) {
        if (!StringUtils.hasText(blob) || !StringUtils.hasText(name)) {
            return -1;
        }
        int exact = blob.indexOf(name);
        if (exact >= 0) {
            return exact;
        }
        int dot = name.indexOf('·');
        if (dot > 0) {
            int base = blob.indexOf(name.substring(0, dot));
            if (base >= 0) {
                return base;
            }
        }
        if (name.length() >= 4) {
            return blob.indexOf(name.substring(name.length() - 2));
        }
        return -1;
    }

    static String join(String... texts) {
        if (texts == null) {
            return "";
        }
        StringBuilder blob = new StringBuilder();
        for (String text : texts) {
            if (!StringUtils.hasText(text)) {
                continue;
            }
            if (!blob.isEmpty()) {
                blob.append(' ');
            }
            blob.append(text.trim());
        }
        return blob.toString();
    }

    private record Hit(String name, int at) {
    }
}
