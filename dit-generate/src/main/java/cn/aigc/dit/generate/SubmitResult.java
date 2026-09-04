package cn.aigc.dit.generate;

/**
 * Provider 提交结果。
 */
public class SubmitResult {
    private final String jobId;
    private final String status;

    public SubmitResult(String jobId, String status) {
        this.jobId = jobId;
        this.status = status;
    }

    public static SubmitResult accepted(String jobId) {
        return new SubmitResult(jobId, "PENDING");
    }

    public String getJobId() {
        return jobId;
    }

    public String getStatus() {
        return status;
    }
}
