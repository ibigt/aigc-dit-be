package cn.aigc.dit.generate.comfyui;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.generate.GenerateProvider;
import cn.aigc.dit.generate.GenerateRequest;
import cn.aigc.dit.generate.PollResult;
import cn.aigc.dit.generate.SubmitResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * ComfyUI Provider 骨架，M3 再补协议对接。
 */
@Component
@ConditionalOnProperty(prefix = "dit.generate.comfyui", name = "enabled", havingValue = "true")
public class ComfyUiGenerateProvider implements GenerateProvider {

    public static final String KEY = "comfyui";

    @Override
    public String providerKey() {
        return KEY;
    }

    @Override
    public SubmitResult submit(GenerateRequest request) {
        throw new BizException("ComfyUI Provider 将在 M3 接入");
    }

    @Override
    public PollResult poll(String jobId) {
        throw new BizException("ComfyUI Provider 将在 M3 接入");
    }

    @Override
    public void cancel(String jobId) {
        throw new BizException("ComfyUI Provider 将在 M3 接入");
    }
}
