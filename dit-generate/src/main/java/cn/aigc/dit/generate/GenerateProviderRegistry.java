package cn.aigc.dit.generate;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
public class GenerateProviderRegistry {

    private final Map<String, GenerateProvider> providers;

    public GenerateProviderRegistry(List<GenerateProvider> providers) {
        this.providers = providers.stream()
                .collect(Collectors.toUnmodifiableMap(
                        p -> p.providerKey().toLowerCase(Locale.ROOT),
                        Function.identity()));
    }

    public GenerateProvider get(String providerKey) {
        GenerateProvider provider = providers.get(providerKey == null ? "" : providerKey.toLowerCase(Locale.ROOT));
        if (provider == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "未知生成 Provider: " + providerKey);
        }
        return provider;
    }

    /**
     * 已注册的 Adapter 按 provider 精确匹配；未知名称仍回退 Mock（ComfyUI 骨架未启用时）。
     */
    public GenerateProvider resolve(String providerKey) {
        if (providerKey == null || providerKey.isBlank()) {
            return mock();
        }
        GenerateProvider provider = providers.get(providerKey.toLowerCase(Locale.ROOT));
        if (provider != null) {
            return provider;
        }
        log.warn("No generate adapter for '{}', falling back to mock", providerKey);
        return mock();
    }

    public GenerateProvider mock() {
        return get("mock");
    }
}
