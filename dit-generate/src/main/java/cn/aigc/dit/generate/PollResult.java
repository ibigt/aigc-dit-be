package cn.aigc.dit.generate;

import java.util.List;

/**
 * Provider 轮询结果。成功时带内部 storage_key 列表（多副本），不含厂商字段。
 */
public class PollResult {
    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    private final String jobId;
    private final String status;
    private final int progress;
    private final String outputStorageKey;
    private final List<String> outputKeys;
    private final String errorMessage;

    public PollResult(String jobId, String status, int progress, String outputStorageKey, String errorMessage) {
        this(jobId, status, progress, outputStorageKey, outputStorageKey == null ? null : List.of(outputStorageKey), errorMessage);
    }

    public PollResult(String jobId, String status, int progress, String outputStorageKey,
                      List<String> outputKeys, String errorMessage) {
        this.jobId = jobId;
        this.status = status;
        this.progress = progress;
        this.outputStorageKey = outputStorageKey;
        this.outputKeys = outputKeys;
        this.errorMessage = errorMessage;
    }

    public static PollResult pending(String jobId, int progress) {
        return new PollResult(jobId, PENDING, progress, null, null, null);
    }

    public static PollResult running(String jobId, int progress) {
        return new PollResult(jobId, RUNNING, progress, null, null, null);
    }

    public static PollResult succeeded(String jobId, String outputStorageKey) {
        return succeeded(jobId, outputStorageKey == null ? List.of() : List.of(outputStorageKey));
    }

    public static PollResult succeeded(String jobId, List<String> outputKeys) {
        List<String> keys = outputKeys == null ? List.of() : List.copyOf(outputKeys);
        String first = keys.isEmpty() ? null : keys.get(0);
        return new PollResult(jobId, SUCCEEDED, 100, first, keys, null);
    }

    public static PollResult failed(String jobId, String errorMessage) {
        return new PollResult(jobId, FAILED, 0, null, null, errorMessage);
    }

    public boolean isTerminal() {
        return SUCCEEDED.equals(status) || FAILED.equals(status) || CANCELLED.equals(status);
    }

    public boolean isSucceeded() {
        return SUCCEEDED.equals(status);
    }

    public String getJobId() {
        return jobId;
    }

    public String getStatus() {
        return status;
    }

    public int getProgress() {
        return progress;
    }

    public String getOutputStorageKey() {
        return outputStorageKey;
    }

    public List<String> getOutputKeys() {
        return outputKeys;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
