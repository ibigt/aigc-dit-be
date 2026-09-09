package cn.aigc.dit.generate;

/**
 * 生成 Provider SPI。运输层：Mock、RunningHub、ComfyUI。
 * 出站字段由 {@link AdapterCatalog}（gpt-image-2 / seedance-2 / minimax-h3）选择翻译包。
 */
public interface GenerateProvider {

    String providerKey();

    SubmitResult submit(GenerateRequest request);

    PollResult poll(String jobId);

    void cancel(String jobId);
}
