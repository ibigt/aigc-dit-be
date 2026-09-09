package cn.aigc.dit.server.bootstrap;

import cn.aigc.dit.common.constant.ModelCategory;
import cn.aigc.dit.server.entity.AiModel;
import cn.aigc.dit.server.mapper.AiModelMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 写入 RunningHub 验证模型。密钥只走环境变量 / 管理端加密字段，不写入本引导。
 */
@Slf4j
@Component
@Order(30)
@RequiredArgsConstructor
public class RunningHubModelBootstrap implements ApplicationRunner {

    public static final String T2I_MODEL_KEY = "gpt-image-2";
    public static final String T2V_MODEL_KEY = "seedance-2.0";

    private final AiModelMapper aiModelMapper;

    @Override
    public void run(ApplicationArguments args) {
        ensureModel(T2I_MODEL_KEY, this::gptImage2);
        ensureModel(T2V_MODEL_KEY, this::seedance20);
    }

    private void ensureModel(String modelKey, Supplier<AiModel> factory) {
        Long exists = aiModelMapper.selectCount(new LambdaQueryWrapper<AiModel>()
                .eq(AiModel::getModelKey, modelKey));
        if (exists != null && exists > 0) {
            return;
        }
        AiModel model = factory.get();
        aiModelMapper.insert(model);
        log.info("Bootstrapped RunningHub model {}", modelKey);
    }

    private AiModel gptImage2() {
        AiModel model = new AiModel();
        model.setName("GPT Image 2");
        model.setModelKey(T2I_MODEL_KEY);
        model.setAdapterKey("gpt-image-2");
        model.setProvider("RunningHub");
        model.setEndpoint("https://www.runninghub.ai");
        model.setCategory(ModelCategory.IMAGE);
        model.setCapabilities(List.of("image", "t2i"));
        model.setAdapterConfig(Map.of("t2iPath", "rhart-image-g-2/text-to-image"));
        model.setPricing(pricing("张", "2 积分", "$0.015"));
        model.setEnabled(true);
        model.setBadge("图像");
        model.setDescription("RunningHub gpt-image-2 文生图验证（1k / low，economy 通道）");
        model.setLogo("/model-logos/openai.svg");
        return model;
    }

    private AiModel seedance20() {
        AiModel model = new AiModel();
        model.setName("Seedance 2.0");
        model.setModelKey(T2V_MODEL_KEY);
        model.setAdapterKey("seedance-2");
        model.setProvider("RunningHub");
        model.setEndpoint("https://www.runninghub.ai");
        model.setCategory(ModelCategory.VIDEO);
        model.setCapabilities(List.of("video", "t2v"));
        model.setAdapterConfig(Map.of("t2vPath", "rhart-video/sparkvideo-2.0/text-to-video"));
        model.setPricing(pricing("次", "12 积分", "5.0"));
        model.setEnabled(true);
        model.setBadge("视频");
        model.setDescription("RunningHub seedance 2.0 文生视频（4–15 秒，默认 720p / 有声）");
        model.setLogo("/model-logos/bytedance-color.svg");
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
