package cn.aigc.dit.server.bootstrap;

import cn.aigc.dit.common.constant.ModelCategory;
import cn.aigc.dit.generate.AdapterCatalog;
import cn.aigc.dit.server.entity.AiModel;
import cn.aigc.dit.server.mapper.AiModelMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 写入 MiniMax-H3 验证模型。验证机地址写在 {@code ai_model.endpoint}，
 * 不改 yml / AdapterCatalog 的 ComfyUI 默认 {@code 127.0.0.1:8188}。
 */
@Slf4j
@Component
@Order(31)
@RequiredArgsConstructor
public class MiniMaxModelBootstrap implements ApplicationRunner {

    public static final String MODEL_KEY = "minimax-h3";
    public static final String DEFAULT_VERIFICATION_ENDPOINT = "http://172.200.0.237:8188";
    public static final List<String> CAPABILITIES = List.of("video", "t2v", "r2v", "i2v");

    private final AiModelMapper aiModelMapper;

    @Override
    public void run(ApplicationArguments args) {
        String endpoint = verificationEndpoint();
        AiModel existing = aiModelMapper.selectOne(new LambdaQueryWrapper<AiModel>()
                .eq(AiModel::getModelKey, MODEL_KEY)
                .last("LIMIT 1"));
        if (existing == null) {
            aiModelMapper.insert(minimaxH3(endpoint));
            log.info("Bootstrapped MiniMax-H3 verification model endpoint={}", endpoint);
            return;
        }
        if (!reconcile(existing, endpoint)) {
            log.info("MiniMax-H3 verification model already aligned id={} endpoint={}", existing.getId(), existing.getEndpoint());
            return;
        }
        aiModelMapper.updateById(existing);
        log.info("Reconciled MiniMax-H3 verification model id={} endpoint={}", existing.getId(), existing.getEndpoint());
    }

    static String verificationEndpoint() {
        String env = System.getenv("MINIMAX_H3_ENDPOINT");
        if (StringUtils.hasText(env)) {
            return env.trim().replaceAll("/+$", "");
        }
        return DEFAULT_VERIFICATION_ENDPOINT;
    }

    static boolean reconcile(AiModel model, String endpoint) {
        boolean dirty = false;
        if (!AdapterCatalog.MINIMAX_H3.equals(model.getAdapterKey())) {
            model.setAdapterKey(AdapterCatalog.MINIMAX_H3);
            dirty = true;
        }
        if (!"ComfyUI".equals(model.getProvider())) {
            model.setProvider("ComfyUI");
            dirty = true;
        }
        if (!Boolean.TRUE.equals(model.getEnabled())) {
            model.setEnabled(true);
            dirty = true;
        }
        if (!endpoint.equals(normalizeEndpoint(model.getEndpoint()))) {
            model.setEndpoint(endpoint);
            dirty = true;
        }
        List<String> caps = AdapterCatalog.constrainCapabilities(CAPABILITIES, ModelCategory.VIDEO,
                AdapterCatalog.require(AdapterCatalog.MINIMAX_H3));
        if (!Objects.equals(caps, model.getCapabilities())) {
            model.setCapabilities(caps);
            dirty = true;
        }
        return dirty;
    }

    static String normalizeEndpoint(String endpoint) {
        if (!StringUtils.hasText(endpoint)) {
            return "";
        }
        return endpoint.trim().replaceAll("/+$", "");
    }

    private static AiModel minimaxH3(String endpoint) {
        AiModel model = new AiModel();
        model.setName("MiniMax-H3");
        model.setModelKey(MODEL_KEY);
        model.setAdapterKey(AdapterCatalog.MINIMAX_H3);
        model.setProvider("ComfyUI");
        model.setEndpoint(endpoint);
        model.setCategory(ModelCategory.VIDEO);
        model.setCapabilities(List.copyOf(CAPABILITIES));
        model.setAdapterConfig(Map.of());
        model.setPricing(pricing("秒", "12 积分", "¥0.42"));
        model.setEnabled(true);
        model.setBadge("视频");
        model.setDescription("本地 MiniMax-H3 验证通道（GB10 / r2v+i2v，5–15 秒）。地址写在本模型 endpoint，不是 yml 默认值。");
        model.setLogo("/model-logos/hailuo-color.svg");
        return model;
    }

    private static Map<String, Object> pricing(String unit, String saleOutput, String costOutput) {
        Map<String, Object> sale = new LinkedHashMap<>();
        sale.put("input", "0");
        sale.put("output", saleOutput);
        sale.put("cache", "0");
        Map<String, Object> cost = new LinkedHashMap<>();
        cost.put("input", "0");
        cost.put("output", costOutput);
        cost.put("cache", "0");
        Map<String, Object> pricing = new LinkedHashMap<>();
        pricing.put("unit", unit);
        pricing.put("sale", sale);
        pricing.put("cost", cost);
        return pricing;
    }
}
