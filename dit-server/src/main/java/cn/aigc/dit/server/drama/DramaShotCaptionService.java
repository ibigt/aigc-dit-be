package cn.aigc.dit.server.drama;

import cn.aigc.dit.generate.GenerateProperties;
import cn.aigc.dit.generate.caption.CaptionCue;
import cn.aigc.dit.generate.caption.VideoCaptionBurner;
import cn.aigc.dit.server.drama.entity.DramaShot;
import cn.aigc.dit.server.drama.mapper.DramaShotMapper;
import cn.aigc.dit.server.entity.GenTask;
import cn.aigc.dit.server.generate.GenerateRequests;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 仅漫剧分镜出片：有对白才把字幕烧进单镜。当前出片链路已暂停调用，改到成片拼接再打开。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DramaShotCaptionService {

    private final DramaShotMapper shotMapper;
    private final VideoCaptionBurner videoCaptionBurner;
    private final GenerateProperties generateProperties;

    public List<String> burnIfNeeded(GenTask task, List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return keys == null ? List.of() : keys;
        }
        if (!ffmpeg().isCaptionBurnEnabled()) {
            return keys;
        }
        if (task == null || !GenerateRequests.isDramaShot(task.getParams())) {
            return keys;
        }
        Long shotId = GenerateRequests.shotIdFromParams(task.getParams());
        if (shotId == null) {
            return keys;
        }
        DramaShot shot = shotMapper.selectById(shotId);
        if (shot == null) {
            return keys;
        }
        int duration = shot.getDurationSec() == null || shot.getDurationSec() < 1
                ? DramaContract.DEFAULT_DURATION_SEC
                : shot.getDurationSec();
        List<CaptionCue> cues = DramaShotCaptions.cues(shot.getDialogue(), duration);
        if (cues.isEmpty()) {
            return keys;
        }
        List<String> burned = new ArrayList<>(keys.size());
        for (String key : keys) {
            try {
                String next = videoCaptionBurner.burn(key, cues);
                burned.add(StringUtils.hasText(next) ? next : key);
            } catch (Exception e) {
                log.warn("drama shot {} caption burn failed, keep original key={}", shotId, key, e);
                burned.add(key);
            }
        }
        return burned;
    }

    private GenerateProperties.Ffmpeg ffmpeg() {
        return generateProperties.getFfmpeg() == null
                ? new GenerateProperties.Ffmpeg()
                : generateProperties.getFfmpeg();
    }
}
