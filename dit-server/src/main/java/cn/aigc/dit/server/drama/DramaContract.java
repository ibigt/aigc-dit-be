package cn.aigc.dit.server.drama;

import java.util.List;
import java.util.Set;

/**
 * 漫剧阶段门用词与基础信息取值。三端（库 / API / 前端）同一套。
 */
public final class DramaContract {

    public static final String STATUS_NOT_STARTED = "未开始";
    public static final String STATUS_PENDING_CONFIRM = "待确认";
    public static final String STATUS_LOCKED = "已锁定";
    public static final String STATUS_EXPIRED = "已过期";

    public static final List<String> STATUSES = List.of(
            STATUS_NOT_STARTED, STATUS_PENDING_CONFIRM, STATUS_LOCKED, STATUS_EXPIRED);

    public static final String DEFAULT_ASPECT_RATIO = "16:9";
    public static final String DEFAULT_RESOLUTION = "720P";
    public static final int DEFAULT_DURATION_SEC = 15;
    public static final int MIN_DURATION_SEC = 5;
    public static final int MAX_DURATION_SEC = 15;
    public static final int DEFAULT_PLANNED_EPISODES = 1;

    /** 画幅候选顺序即前端展示顺序。 */
    public static final List<String> ASPECT_RATIO_OPTIONS = List.of("21:9", "16:9", "4:3", "1:1", "3:4", "9:16");
    /** 清晰度候选顺序由低到高，clamp 依赖该顺序。 */
    public static final List<String> RESOLUTION_OPTIONS = List.of("480P", "720P", "1080P", "2K");

    public static final Set<String> ASPECT_RATIOS = Set.copyOf(ASPECT_RATIO_OPTIONS);
    public static final Set<String> RESOLUTIONS = Set.copyOf(RESOLUTION_OPTIONS);
    public static final List<Integer> DURATION_OPTIONS = List.of(5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15);
    public static final Set<Integer> DURATIONS = Set.copyOf(DURATION_OPTIONS);

    /** 尚未用文本模型成功解析。 */
    public static final String ENGINE_PENDING = "pending";
    /** 历史规则分场，仅兼容旧数据；新解析不再写入。 */
    public static final String ENGINE_RULES = "rules";
    /** 文本模型分场。 */
    public static final String ENGINE_LLM = "llm";
    /** 失败原因落库上限，避免把整段模型原文写进剧集行。 */
    public static final int ERROR_MESSAGE_MAX = 500;

    public static final Set<String> ENGINES = Set.of(ENGINE_PENDING, ENGINE_RULES, ENGINE_LLM);

    public static final String ASSET_TYPE_CHARACTER = "角色";
    public static final String ASSET_TYPE_SCENE = "场景";
    public static final String ASSET_TYPE_PROP = "道具";
    public static final Set<String> ASSET_TYPES = Set.of(
            ASSET_TYPE_CHARACTER, ASSET_TYPE_SCENE, ASSET_TYPE_PROP);

    public static final String STATE_TAG_BASE = "base";
    public static final String STATE_TAG_DREAM = "dream";
    public static final String STATE_TAG_COSTUME = "costume";

    /** 资产页同时进行的 t2i 上限（支持一次性批量 16 张）；超出部分由前端滚动排队补位。 */
    public static final int MAX_ASSET_GENERATE = 16;
    /** 同时向模型提交的分镜出片上限；超出的任务保持排队，空出槽位后再提交。 */
    public static final int MAX_SHOT_GENERATE = 8;

    /** 单场分镜下限（乙/规则草稿）。 */
    public static final int MIN_SHOTS_PER_CHAPTER = 2;
    /** 单场分镜上限。 */
    public static final int MAX_SHOTS_PER_CHAPTER = 12;
    /** 第一场验收：4–8 镜。 */
    public static final int FIRST_SCENE_MIN_SHOTS = 4;
    public static final int FIRST_SCENE_MAX_SHOTS = 8;

    private DramaContract() {
    }

    public static String normalizeEngine(String engine) {
        if (ENGINE_RULES.equals(engine) || ENGINE_LLM.equals(engine)) {
            return engine;
        }
        return ENGINE_PENDING;
    }

    public static String clipError(String message) {
        if (message == null || message.isBlank()) {
            return "文本模型调用失败";
        }
        String text = message.trim();
        return text.length() <= ERROR_MESSAGE_MAX ? text : text.substring(0, ERROR_MESSAGE_MAX);
    }
}
