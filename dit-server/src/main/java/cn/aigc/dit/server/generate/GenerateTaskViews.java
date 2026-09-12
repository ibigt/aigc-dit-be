package cn.aigc.dit.server.generate;

import cn.aigc.dit.common.constant.TaskStatus;
import cn.aigc.dit.generate.AdapterCatalog;
import cn.aigc.dit.server.entity.GenTask;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Map;

/**
 * 对外暴露任务状态：官方 Seedance 被旧 Agnes 进程误杀时，界面仍显示生成中。
 */
public final class GenerateTaskViews {

    public static final String OFFICIAL_ERROR_KEY = "officialError";
    public static final String REAL_PERSON_MESSAGE =
            "参考图可能包含真人，官方 Seedance 已拒绝。请更换不含真人脸的参考图，或改用 MiniMax 等本地模型。";
    public static final String FOREIGN_SUBMIT_FALLBACK =
            "官方 Seedance 未接受这次提交。参考图若含真人脸会被接口拒绝，请换图后重试，或改用 MiniMax。";

    private GenerateTaskViews() {
    }

    public static boolean isOfficialSeedance(GenTask task) {
        if (task == null) {
            return false;
        }
        String adapter = text(task, "adapterKey");
        if (AdapterCatalog.isArk(adapter)) {
            return true;
        }
        return AdapterCatalog.looksLikeOfficialSeedance(text(task, "modelKey"));
    }

    public static boolean isForeignAgnesError(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String value = message.trim();
        return value.startsWith("Agnes") || value.contains("Agnes ");
    }

    public static boolean shouldKeepPolling(GenTask task) {
        return task != null
                && isOfficialSeedance(task)
                && StringUtils.hasText(task.getProviderJobId())
                && TaskStatus.FAILED.equals(task.getStatus())
                && isForeignAgnesError(task.getErrorMessage());
    }

    public static String publicStatus(GenTask task) {
        if (shouldKeepPolling(task)) {
            return TaskStatus.RUNNING;
        }
        return task == null ? null : task.getStatus();
    }

    public static String publicError(GenTask task) {
        if (task == null) {
            return null;
        }
        if (shouldKeepPolling(task)) {
            return null;
        }
        if (isOfficialSeedance(task) && isForeignAgnesError(task.getErrorMessage())) {
            String official = officialError(task);
            if (StringUtils.hasText(official)) {
                return friendlySeedanceError(official);
            }
            return FOREIGN_SUBMIT_FALLBACK;
        }
        return friendlySeedanceError(task.getErrorMessage());
    }

    public static String officialError(GenTask task) {
        if (task == null || task.getParams() == null || task.getParams().get(OFFICIAL_ERROR_KEY) == null) {
            return null;
        }
        String value = String.valueOf(task.getParams().get(OFFICIAL_ERROR_KEY)).trim();
        return value.isBlank() ? null : value;
    }

    public static String friendlySeedanceError(String message) {
        if (message == null || message.isBlank()) {
            return message;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("real person") || message.contains("真人")) {
            return REAL_PERSON_MESSAGE;
        }
        return message;
    }

    private static String text(GenTask task, String key) {
        Map<String, Object> params = task.getParams();
        if (params == null || params.get(key) == null) {
            return null;
        }
        String value = String.valueOf(params.get(key)).trim();
        return value.isBlank() ? null : value;
    }
}
