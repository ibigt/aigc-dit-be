package cn.aigc.dit.server.drama.agent;

/**
 * Supervisor 校验出的具体问题。
 *
 * @param field      出问题的字段路径（例：shots[2].duration_sec）
 * @param severity   严重等级（ERROR / WARNING）
 * @param message    给人类看的描述
 * @param suggestion 修复建议（注入到 prompt 时使用）
 */
public record SupervisorIssue(
        String field,
        String severity,
        String message,
        String suggestion
) {

    /** 严重等级：必须修复，否则结果不可用。 */
    public static final String ERROR = "error";
    /** 严重等级：建议修复，不影响流程继续。 */
    public static final String WARNING = "warning";

    public SupervisorIssue {
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("field must not be blank");
        }
        if (severity == null) {
            throw new IllegalArgumentException("severity must not be null");
        }
        if (!ERROR.equals(severity) && !WARNING.equals(severity)) {
            throw new IllegalArgumentException("severity must be ERROR or WARNING");
        }
        if (message == null) {
            throw new IllegalArgumentException("message must not be null");
        }
    }

    public boolean isError() {
        return ERROR.equals(severity);
    }

    public boolean isWarning() {
        return WARNING.equals(severity);
    }
}
