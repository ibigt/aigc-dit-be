package cn.aigc.dit.server.drama;

import cn.aigc.dit.server.drama.entity.DramaMemory;
import cn.aigc.dit.server.drama.mapper.DramaMemoryMapper;
import cn.aigc.dit.server.drama.text.ScriptDoc;
import cn.aigc.dit.server.drama.text.SkeletonDoc;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 项目级记忆服务。把跨集复用的人物 / 世界观 / 风格摘要持久化到 drama_memory，
 * 供后续集生成时注入 system prompt，节省 token + 减少人物漂移。
 *
 * 4 个 type：
 * - characters   跨集人物清单（合并 ScriptDoc.characters + SkeletonDoc.characters_recurrent 去重）
 * - world        世界观规则（SkeletonDoc.world_rules 拼接）
 * - user_pref    项目基础信息里的视觉风格 + 时长偏好
 * - style_pref   上一集的 style_prefix 复用
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectMemoryService {

    private final DramaMemoryMapper mapper;

    /** 记忆类型：人物。 */
    public static final String TYPE_CHARACTERS = "characters";
    /** 记忆类型：世界观。 */
    public static final String TYPE_WORLD = "world";
    /** 记忆类型：用户偏好。 */
    public static final String TYPE_USER_PREF = "user_pref";
    /** 记忆类型：风格偏好。 */
    public static final String TYPE_STYLE_PREF = "style_pref";

    /** characters 记忆上限。 */
    private static final int MAX_CHARACTERS = 20;
    /** world_rules 上限。 */
    private static final int MAX_WORLD_RULES = 5;
    /** 单条 memory content 最大字符数（约 800 token 上限留余）。 */
    private static final int MAX_CONTENT_LENGTH = 2400;

    /**
     * 解析完一集后增量写入。把 ScriptDoc + SkeletonDoc 里的稳定信息合并去重落库。
     */
    public void recordAfterParse(long seriesId, long episodeId,
                                  ScriptDoc scriptDoc, SkeletonDoc skeleton) {
        // 1. characters
        Set<String> characters = new LinkedHashSet<>();
        if (skeleton != null && skeleton.charactersRecurrent() != null) {
            characters.addAll(skeleton.charactersRecurrent());
        }
        if (scriptDoc != null && scriptDoc.getScenes() != null) {
            scriptDoc.getScenes().stream()
                    .filter(s -> s.getCharacters() != null)
                    .flatMap(s -> s.getCharacters().stream())
                    .forEach(characters::add);
        }
        if (!characters.isEmpty()) {
            String content = truncate(characters.stream().limit(MAX_CHARACTERS).collect(Collectors.toList()));
            mapper.upsert(seriesId, TYPE_CHARACTERS, content, episodeId);
        }

        // 2. world
        if (skeleton != null && skeleton.worldRules() != null && !skeleton.worldRules().isEmpty()) {
            String content = truncate(skeleton.worldRules().stream().limit(MAX_WORLD_RULES).toList());
            mapper.upsert(seriesId, TYPE_WORLD, content, episodeId);
        }

        // 3. style_pref：复用最新一集的 style_prefix
        if (scriptDoc != null && scriptDoc.getStylePrefix() != null
                && !scriptDoc.getStylePrefix().isBlank()) {
            mapper.upsert(seriesId, TYPE_STYLE_PREF, scriptDoc.getStylePrefix(), episodeId);
        }
    }

    /**
     * 把记忆块渲染为注入 system prompt 的 Markdown 文本。
     * 按 score 截断：characters 优先（必有），world 次之，style_pref 再次，user_pref 末位。
     */
    public String resolveMemoryBlock(long seriesId) {
        List<DramaMemory> all = mapper.listBySeries(seriesId);
        if (all.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        appendIfPresent(sb, all, TYPE_CHARACTERS, "跨集人物");
        appendIfPresent(sb, all, TYPE_WORLD, "世界观规则");
        appendIfPresent(sb, all, TYPE_STYLE_PREF, "本剧风格");
        appendIfPresent(sb, all, TYPE_USER_PREF, "用户偏好");

        String block = sb.toString();
        if (block.length() <= MAX_CONTENT_LENGTH) {
            return block;
        }
        return block.substring(0, MAX_CONTENT_LENGTH) + "\n…(已截断)";
    }

    /** 写入用户偏好（由上游项目设置变更时调用）。 */
    public void recordUserPref(long seriesId, String content) {
        if (content == null || content.isBlank()) return;
        mapper.upsert(seriesId, TYPE_USER_PREF, truncate(List.of(content)), null);
    }

    private void appendIfPresent(StringBuilder sb, List<DramaMemory> all, String type, String title) {
        all.stream()
                .filter(m -> type.equals(m.getType()))
                .findFirst()
                .ifPresent(m -> {
                    sb.append("\n【").append(title).append("】\n");
                    sb.append(m.getContent()).append("\n");
                });
    }

    private String truncate(List<String> items) {
        String joined = String.join("、", items);
        if (joined.length() <= MAX_CONTENT_LENGTH) return joined;
        return joined.substring(0, MAX_CONTENT_LENGTH) + "…";
    }
}
