package cn.aigc.dit.server.drama.text;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * 分场 / 分镜 LLM 请求分片。
 * <p>
 * 分场：仅当原文超字符预算时按场标记打包（短剧本保持单次，避免无谓变慢）。
 * 分镜：默认最多 {@link #MAX_SCENES_PER_SHOT_CHUNK} 场/片，并按对白条数预算自动少并场
 * （过密缩到 1～2 场/片，单场再密也单独成片）；截断时仍会缩片重跑。
 * 失败只重跑坏片，不把整本原样重试。
 */
public final class DramaTextChunker {

    /**
     * 分场 user 侧剧本载荷软预算（字符）。超过才按场打包；单段再大也单独成片。
     */
    public static final int CHAR_BUDGET = 5000;

    /**
     * 分场截断后细拆时，无场标记长文的切段预算（仅失败重试路径使用）。
     */
    public static final int CHAR_BUDGET_AFTER_TRUNCATION = 1800;

    /**
     * 已废弃强制按场数切分：短剧本多场标记不应为「稳」而付出 N 次串行 LLM。
     * 保留常量供测试/兼容调用，{@link #packRawChunks(String)} 不再使用强制场数阈值。
     */
    public static final int FORCE_PARSE_SPLIT_SEGMENTS = Integer.MAX_VALUE;

    /** 分场每片最多合并的场标记段数（仅在超预算打包时生效）。 */
    public static final int MAX_SCENES_PER_PARSE_CHUNK = 2;

    /** 分镜每片最多场次数（对白很少时才吃满）。 */
    public static final int MAX_SCENES_PER_SHOT_CHUNK = 3;

    /**
     * 分镜单片对白条数软预算。累加将超预算则先封片，从而自动缩成 1～2 场；
     * 单场对白超过预算时仍单独成片。
     */
    public static final int SHOT_DIALOGUE_LINE_BUDGET = 24;

    private DramaTextChunker() {
    }

    /**
     * 原文按场标记切段后打包。整本短且场数少时保持单片；否则按预算与每片场数上限切。
     */
    public static List<String> packRawChunks(String raw) {
        return packRawChunks(raw, CHAR_BUDGET, FORCE_PARSE_SPLIT_SEGMENTS, MAX_SCENES_PER_PARSE_CHUNK);
    }

    static List<String> packRawChunks(String raw, int budget) {
        return packRawChunks(raw, budget, FORCE_PARSE_SPLIT_SEGMENTS, MAX_SCENES_PER_PARSE_CHUNK);
    }

    static List<String> packRawChunks(String raw, int budget, int forceSplitSegments, int maxScenesPerChunk) {
        String text = raw == null ? "" : raw;
        int limit = Math.max(1, budget);
        int forceAt = Math.max(2, forceSplitSegments);
        int maxPer = Math.max(1, maxScenesPerChunk);
        List<String> segments = ScriptParser.splitRawSegments(text);
        if (segments.size() <= 1) {
            // 无场标记时：超预算按段落/长度预切，避免 MiniMax 整本一次输出必炸；
            // 未超预算仍单片，不影响短剧本与正常模型速度。
            return splitOversizedUnmarked(text, limit);
        }
        if (text.length() <= limit && segments.size() < forceAt) {
            return List.of(text);
        }
        List<List<String>> packs = packItems(segments, String::length, limit, maxPer);
        List<String> chunks = new ArrayList<>(packs.size());
        for (List<String> pack : packs) {
            chunks.add(String.join("\n\n", pack));
        }
        return chunks;
    }

    /**
     * 分场被 max_tokens 截断后的细拆：有场标记则每场一片；无标记则按段落/长度切。
     * 仅失败重试调用，不影响正常模型的首次打包。
     */
    public static List<String> packRawChunksAfterTruncation(String raw) {
        String text = raw == null ? "" : raw;
        List<String> segments = ScriptParser.splitRawSegments(text);
        if (segments.size() > 1) {
            return List.copyOf(segments);
        }
        int budget = Math.min(CHAR_BUDGET_AFTER_TRUNCATION, Math.max(800, text.length() / 2));
        return splitOversizedUnmarked(text, budget);
    }

    /**
     * 无场标记长文：优先空行切段，仍过大再硬切。
     */
    static List<String> splitOversizedUnmarked(String raw, int budget) {
        String text = raw == null ? "" : raw;
        int limit = Math.max(1, budget);
        if (text.length() <= limit) {
            return List.of(text);
        }
        String[] paragraphs = text.split("\\n\\s*\\n");
        if (paragraphs.length <= 1) {
            List<String> hard = new ArrayList<>();
            for (int i = 0; i < text.length(); i += limit) {
                hard.add(text.substring(i, Math.min(text.length(), i + limit)));
            }
            return hard;
        }
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : paragraphs) {
            String piece = paragraph == null ? "" : paragraph.strip();
            if (piece.isEmpty()) {
                continue;
            }
            if (current.length() > 0 && current.length() + piece.length() + 2 > limit) {
                chunks.add(current.toString());
                current = new StringBuilder();
            }
            if (current.length() > 0) {
                current.append("\n\n");
            }
            current.append(piece);
            if (current.length() >= limit) {
                chunks.add(current.toString());
                current = new StringBuilder();
            }
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return chunks.isEmpty() ? List.of(text) : chunks;
    }

    /**
     * 分镜用：按场数上限 + 对白条数预算打包；对白过密时自动缩到 1～2 场/片。
     */
    public static List<List<ScriptDoc.Scene>> packScenes(List<ScriptDoc.Scene> scenes) {
        return packScenesForShots(scenes, MAX_SCENES_PER_SHOT_CHUNK, SHOT_DIALOGUE_LINE_BUDGET);
    }

    /**
     * 按字符预算打包场次（测试 / 兼容路径）。
     */
    static List<List<ScriptDoc.Scene>> packScenes(List<ScriptDoc.Scene> scenes, int budget) {
        List<ScriptDoc.Scene> list = scenes == null ? List.of() : scenes;
        if (list.isEmpty()) {
            return List.of();
        }
        int limit = Math.max(1, budget);
        int total = 0;
        for (ScriptDoc.Scene scene : list) {
            total += estimateSceneChars(scene);
        }
        if (list.size() == 1 || total <= limit) {
            return List.of(List.copyOf(list));
        }
        return packItems(list, DramaTextChunker::estimateSceneChars, limit, Integer.MAX_VALUE);
    }

    static List<List<ScriptDoc.Scene>> packScenesForShots(List<ScriptDoc.Scene> scenes, int maxPerChunk) {
        return packScenesForShots(scenes, maxPerChunk, SHOT_DIALOGUE_LINE_BUDGET);
    }

    static List<List<ScriptDoc.Scene>> packScenesForShots(
            List<ScriptDoc.Scene> scenes, int maxPerChunk, int dialogueLineBudget) {
        List<ScriptDoc.Scene> list = scenes == null ? List.of() : scenes;
        if (list.isEmpty()) {
            return List.of();
        }
        int maxPer = Math.max(1, maxPerChunk);
        int dlgBudget = Math.max(1, dialogueLineBudget);
        int totalDlg = 0;
        for (ScriptDoc.Scene scene : list) {
            totalDlg += dialogueLineCount(scene);
        }
        if (list.size() <= maxPer && totalDlg <= dlgBudget) {
            return List.of(List.copyOf(list));
        }
        return packItems(list, DramaTextChunker::dialogueLineCount, dlgBudget, maxPer);
    }

    /** 有效对白条数；无对白场按 1 计，避免零重量把过多场塞进一片。 */
    static int dialogueLineCount(ScriptDoc.Scene scene) {
        if (scene == null) {
            return 1;
        }
        int count = 0;
        for (ScriptDoc.Dialogue line : safe(scene.getDialogue())) {
            if (line == null) {
                continue;
            }
            if (StringUtils.hasText(line.getLine()) || StringUtils.hasText(line.getSpeaker())) {
                count++;
            }
        }
        return Math.max(1, count);
    }

    static <T> List<List<T>> packItems(List<T> items, ToIntFunction<T> sizeOf, int budget) {
        return packItems(items, sizeOf, budget, Integer.MAX_VALUE);
    }

    static <T> List<List<T>> packItems(List<T> items, ToIntFunction<T> sizeOf, int budget, int maxCount) {
        List<List<T>> packs = new ArrayList<>();
        List<T> current = new ArrayList<>();
        int used = 0;
        int limit = Math.max(1, budget);
        int cap = Math.max(1, maxCount);
        for (T item : items) {
            int size = Math.max(1, sizeOf.applyAsInt(item));
            boolean overBudget = !current.isEmpty() && used + size > limit;
            boolean overCount = !current.isEmpty() && current.size() >= cap;
            if (overBudget || overCount) {
                packs.add(List.copyOf(current));
                current = new ArrayList<>();
                used = 0;
            }
            current.add(item);
            used += size;
        }
        if (!current.isEmpty()) {
            packs.add(List.copyOf(current));
        }
        return packs;
    }

    /**
     * 粗估单场 JSON 体积，避免为分片再序列化整本。
     */
    static int estimateSceneChars(ScriptDoc.Scene scene) {
        if (scene == null) {
            return 1;
        }
        int total = 64;
        total += length(scene.getSlug());
        total += length(scene.getHeading());
        total += length(scene.getLocation());
        total += length(scene.getTime());
        total += length(scene.getAction());
        total += length(scene.getSound());
        for (String name : safe(scene.getCharacters())) {
            total += length(name) + 4;
        }
        for (String name : safe(scene.getProps())) {
            total += length(name) + 4;
        }
        for (ScriptDoc.Dialogue line : safe(scene.getDialogue())) {
            if (line == null) {
                continue;
            }
            total += length(line.getSpeaker()) + length(line.getLine()) + 16;
        }
        return Math.max(1, total);
    }

    private static int length(String value) {
        return StringUtils.hasText(value) ? value.length() : 0;
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }
}
