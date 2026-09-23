package cn.aigc.dit.generate;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;

/**
 * 生成 Provider SPI。运输层：Mock、RunningHub、ComfyUI、Agnes（官方API）。
 * 出站字段由 {@link AdapterCatalog} 选择翻译包。
 */
public interface GenerateProvider {

    String providerKey();

    SubmitResult submit(GenerateRequest request);

    PollResult poll(String jobId);

    void cancel(String jobId);

    /**
     * 管理端连通性测试：只允许零消耗的只读探活（查询任务 / 模型列表 / system_stats 等），
     * 禁止调用会产生费用或生成任务的接口；失败抛 {@link BizException}。
     */
    default void ping(GenerateRequest request) {
        throw new BizException(ErrorCode.BAD_REQUEST, "该适配协议暂不支持测试连接");
    }
}
