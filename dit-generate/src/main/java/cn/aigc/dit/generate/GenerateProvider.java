package cn.aigc.dit.generate;

/**
 * 生成 Provider SPI。运输层：Mock、RunningHub、ComfyUI、Agnes（官方API）。
 * 出站字段由 {@link AdapterCatalog} 选择翻译包。
 */
public interface GenerateProvider {

    String providerKey();

    SubmitResult submit(GenerateRequest request);

    PollResult poll(String jobId);

    void cancel(String jobId);
}
