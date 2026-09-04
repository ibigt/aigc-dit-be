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

/**
 * 空库时写入 Mock 图像 / 视频模型，保证快速创作闭环可直接跑。
 */
@Slf4j
@Component
@Order(20)
@RequiredArgsConstructor
public class MockModelBootstrap implements ApplicationRunner {

    private final AiModelMapper aiModelMapper;

    @Override
    public void run(ApplicationArguments args) {
        long exists = aiModelMapper.selectCount(new LambdaQueryWrapper<>());
        if (exists > 0) {
            return;
        }
        aiModelMapper.insert(model(
                "光驭 Mock 图像",
                "mock-image",
                ModelCategory.IMAGE,
                "张",
                "3 积分",
                List.of("image", "t2i", "i2i"),
                "图像",
                "无 GPU 时的占位图像模型"));
        aiModelMapper.insert(model(
                "光驭 Mock 视频",
                "mock-video",
                ModelCategory.VIDEO,
                "秒",
                "12 积分",
                List.of("video", "t2v", "i2v", "frames"),
                "视频",
                "无 GPU 时的占位视频模型"));
        log.info("Bootstrapped mock image/video models");
    }

    private static AiModel model(String name, String key, String category, String unit, String sale,
                                 List<String> capabilities, String badge, String description) {
        Map<String, Object> saleMap = new LinkedHashMap<>();
        saleMap.put("input", "0");
        saleMap.put("output", sale);
        saleMap.put("cache", "0");
        Map<String, Object> pricing = new LinkedHashMap<>();
        pricing.put("unit", unit);
        pricing.put("sale", saleMap);
        AiModel model = new AiModel();
        model.setName(name);
        model.setModelKey(key);
        model.setProvider("mock");
        model.setCategory(category);
        model.setCapabilities(capabilities);
        model.setPricing(pricing);
        model.setEnabled(true);
        model.setBadge(badge);
        model.setDescription(description);
        model.setLogo("/model-logos/comfyui-color.svg");
        return model;
    }
}
