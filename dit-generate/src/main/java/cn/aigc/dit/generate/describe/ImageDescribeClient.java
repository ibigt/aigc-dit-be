package cn.aigc.dit.generate.describe;

/**
 * 图 → 文（多模态）。
 */
public interface ImageDescribeClient {

    /**
     * @param imageBytes 原图像素
     * @param mimeType   如 image/jpeg
     * @param userText   描述指令
     * @param endpoint   方舟 OpenAPI 根，如 https://ark.cn-beijing.volces.com/api/v3 或 …/api/plan/v3
     * @param apiKey     Bearer Key
     * @param visionModel 视觉模型 ID
     */
    ImageDescribeResult describe(
            byte[] imageBytes,
            String mimeType,
            String userText,
            String endpoint,
            String apiKey,
            String visionModel);
}
