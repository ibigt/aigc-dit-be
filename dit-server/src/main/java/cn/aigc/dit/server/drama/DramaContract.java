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
    public static final int DEFAULT_PLANNED_EPISODES = 1;

    public static final Set<String> ASPECT_RATIOS = Set.of("16:9", "9:16", "1:1");
    public static final Set<String> RESOLUTIONS = Set.of("480P", "720P");
    public static final Set<Integer> DURATIONS = Set.of(5, 10, 15);

    /** 乙未合入：导入只存原文。 */
    public static final String ENGINE_PENDING = "pending";
    /** 规则分场（无可用文本模型）。 */
    public static final String ENGINE_RULES = "rules";
    /** 文本模型分场。 */
    public static final String ENGINE_LLM = "llm";

    public static final Set<String> ENGINES = Set.of(ENGINE_PENDING, ENGINE_RULES, ENGINE_LLM);

    public static final String ASSET_TYPE_CHARACTER = "角色";
    public static final String ASSET_TYPE_SCENE = "场景";
    public static final String ASSET_TYPE_PROP = "道具";
    public static final Set<String> ASSET_TYPES = Set.of(
            ASSET_TYPE_CHARACTER, ASSET_TYPE_SCENE, ASSET_TYPE_PROP);

    public static final String STATE_TAG_BASE = "base";
    public static final String STATE_TAG_DREAM = "dream";
    public static final String STATE_TAG_COSTUME = "costume";

    /** 资产页同时进行的 t2i 上限。 */
    public static final int MAX_ASSET_GENERATE = 4;
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
}
