package cn.aigc.dit.common.constant;

/**
 * 生成任务状态。
 */
public final class TaskStatus {
    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    private TaskStatus() {
    }
}
