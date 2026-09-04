package cn.aigc.dit.common.constant;

/**
 * 模型 / 任务类别。
 */
public final class ModelCategory {
    public static final String IMAGE = "image";
    public static final String VIDEO = "video";
    public static final String TOOL = "tool";

    private ModelCategory() {
    }

    public static boolean isCreatable(String category) {
        return IMAGE.equals(category) || VIDEO.equals(category);
    }
}
