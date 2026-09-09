package cn.aigc.dit.server.bootstrap;

import cn.aigc.dit.generate.GenerateMode;
import cn.aigc.dit.server.entity.AiModel;
import cn.aigc.dit.server.entity.GenTask;
import cn.aigc.dit.server.mapper.AiModelMapper;
import cn.aigc.dit.server.mapper.GenTaskMapper;
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
import java.util.Objects;

/**
 * V1 已执行过的库不会重跑脚本；启动时把遗留 v2v/frames 规范为 i2v。
 */
@Slf4j
@Component
@Order(15)
@RequiredArgsConstructor
public class LegacyModeRenameBootstrap implements ApplicationRunner {

    private final AiModelMapper aiModelMapper;
    private final GenTaskMapper genTaskMapper;

    @Override
    public void run(ApplicationArguments args) {
        int models = 0;
        for (AiModel model : aiModelMapper.selectList(null)) {
            if (!renameModel(model)) {
                continue;
            }
            aiModelMapper.updateById(model);
            models++;
        }
        int tasks = 0;
        List<GenTask> legacyTasks = genTaskMapper.selectList(new LambdaQueryWrapper<GenTask>()
                .apply("params->>'mode' IN ('v2v', 'frames', 'frame')"));
        for (GenTask task : legacyTasks) {
            if (!renameTask(task)) {
                continue;
            }
            genTaskMapper.updateById(task);
            tasks++;
        }
        if (models > 0 || tasks > 0) {
            log.info("Normalized first/last-frame mode v2v→i2v: models={}, tasks={}", models, tasks);
        }
    }

    private static boolean renameModel(AiModel model) {
        boolean changed = false;
        List<String> caps = GenerateMode.canonicalizeCapabilities(model.getCapabilities());
        if (caps != null && !Objects.equals(caps, model.getCapabilities())) {
            model.setCapabilities(caps);
            changed = true;
        }
        Map<String, Object> config = model.getAdapterConfig();
        if (config != null && config.containsKey("v2vPath")) {
            Map<String, Object> next = new LinkedHashMap<>(config);
            if (!next.containsKey("i2vPath")) {
                next.put("i2vPath", next.get("v2vPath"));
            }
            next.remove("v2vPath");
            model.setAdapterConfig(next);
            changed = true;
        }
        return changed;
    }

    private static boolean renameTask(GenTask task) {
        Map<String, Object> params = task.getParams();
        if (params == null) {
            return false;
        }
        Object mode = params.get("mode");
        if (!(mode instanceof String value)) {
            return false;
        }
        String canonical = GenerateMode.canonical(value);
        if (value.equals(canonical)) {
            return false;
        }
        Map<String, Object> next = new LinkedHashMap<>(params);
        next.put("mode", canonical);
        task.setParams(next);
        return true;
    }
}
