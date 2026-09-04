package cn.aigc.dit.generate;

/**
 * 生成 Provider SPI。一期用 Mock，后续只换实现即可接 ComfyUI / 付费 API。
 */
public interface GenerateProvider {

    String providerKey();

    SubmitResult submit(GenerateRequest request);

    PollResult poll(String jobId);

    void cancel(String jobId);
}
