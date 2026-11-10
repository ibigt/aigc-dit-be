package cn.aigc.dit.server.drama.agent;

import java.util.Collections;
import java.util.List;

/**
 * Supervisor 校验结果。
 *
 * @param passed  是否通过
 * @param issues  具体问题列表（passed=true 时为空）
 */
public record SupervisorVerdict(
        boolean passed,
        List<SupervisorIssue> issues
) {
    public static SupervisorVerdict pass() {
        return new SupervisorVerdict(true, Collections.emptyList());
    }

    public static SupervisorVerdict fail(List<SupervisorIssue> issues) {
        return new SupervisorVerdict(false, List.copyOf(issues));
    }

    public boolean hasErrors() {
        return issues.stream().anyMatch(SupervisorIssue::isError);
    }

    /**
     * 把 issues 渲染为给 Agent 修复 prompt 用的 Markdown bullet 列表。
     */
    public String toRepairFeedbackMarkdown() {
        if (issues.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n【Supervisor 反馈，请修复以下问题】\n");
        for (SupervisorIssue issue : issues) {
            sb.append("- [").append(issue.severity()).append("] ")
              .append(issue.field()).append(": ")
              .append(issue.message());
            if (issue.suggestion() != null && !issue.suggestion().isBlank()) {
                sb.append(" → 建议：").append(issue.suggestion());
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
