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
    public static final int DEFAULT_DURATION_SEC = 5;
    public static final int DEFAULT_PLANNED_EPISODES = 1;

    public static final Set<String> ASPECT_RATIOS = Set.of("16:9", "9:16", "1:1");
    public static final Set<String> RESOLUTIONS = Set.of("480P", "720P");
    public static final Set<Integer> DURATIONS = Set.of(5, 10, 15);

    private DramaContract() {
    }
}
