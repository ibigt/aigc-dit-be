package cn.aigc.dit.server.drama;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.generate.ffmpeg.FfmpegSupport;
import cn.aigc.dit.server.drama.entity.DramaEpisode;
import cn.aigc.dit.server.drama.entity.DramaShot;
import cn.aigc.dit.server.drama.mapper.DramaEpisodeMapper;
import cn.aigc.dit.server.drama.mapper.DramaShotMapper;
import cn.aigc.dit.storage.ObjectStorage;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DramaCaptionService {

    private final DramaEpisodeMapper episodeMapper;
    private final DramaShotMapper shotMapper;
    private final ObjectStorage objectStorage;

    public String shotVtt(Long shotId) {
        DramaShot shot = requireShot(shotId);
        double duration = actualDuration(shot);
        return DramaCaptionVtt.shot(shot.getDialogue(), duration);
    }

    public String episodeVtt(Long episodeId) {
        DramaEpisode episode = requireEpisode(episodeId);
        if (!StringUtils.hasText(episode.getFilmKey())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先拼接成片");
        }
        String key = captionKey(episode.getFilmKey());
        try {
            return new String(objectStorage.getBytes(key), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            List<DramaShot> clips = DramaFilms.clips(listShots(episodeId));
            String vtt = buildFromStorage(clips);
            objectStorage.put(key, vtt.getBytes(StandardCharsets.UTF_8), "text/vtt; charset=UTF-8");
            return vtt;
        }
    }

    public void storeEpisodeVtt(String filmKey, List<DramaShot> clips, List<Path> localInputs) {
        if (!StringUtils.hasText(filmKey)) {
            return;
        }
        List<DramaCaptionVtt.Shot> tracks = new ArrayList<>();
        for (int i = 0; i < clips.size(); i++) {
            DramaShot shot = clips.get(i);
            Path input = localInputs != null && i < localInputs.size() ? localInputs.get(i) : null;
            tracks.add(new DramaCaptionVtt.Shot(shot.getDialogue(), duration(input, fallbackDuration(shot))));
        }
        String vtt = DramaCaptionVtt.render(tracks);
        objectStorage.put(captionKey(filmKey), vtt.getBytes(StandardCharsets.UTF_8), "text/vtt; charset=UTF-8");
    }

    public void deleteEpisodeVtt(String filmKey) {
        if (!StringUtils.hasText(filmKey)) {
            return;
        }
        try {
            objectStorage.delete(captionKey(filmKey));
        } catch (Exception e) {
            log.debug("delete drama captions failed, key={}", captionKey(filmKey), e);
        }
    }

    static String captionKey(String filmKey) {
        int dot = filmKey == null ? -1 : filmKey.lastIndexOf('.');
        String base = dot > 0 ? filmKey.substring(0, dot) : filmKey;
        return base + "-captions.vtt";
    }

    private String buildFromStorage(List<DramaShot> clips) {
        List<DramaCaptionVtt.Shot> tracks = new ArrayList<>();
        for (DramaShot shot : clips) {
            tracks.add(new DramaCaptionVtt.Shot(shot.getDialogue(), actualDuration(shot)));
        }
        return DramaCaptionVtt.render(tracks);
    }

    private double actualDuration(DramaShot shot) {
        if (shot == null || !StringUtils.hasText(shot.getVideoKey())) {
            return fallbackDuration(shot);
        }
        Path input = null;
        try {
            input = FfmpegSupport.tempFile("dit-caption-probe-", suffix(shot.getVideoKey()));
            Files.write(input, objectStorage.getBytes(shot.getVideoKey()));
            return duration(input, fallbackDuration(shot));
        } catch (Exception e) {
            log.warn("probe drama shot duration failed, shot={}", shot.getId(), e);
            return fallbackDuration(shot);
        } finally {
            deleteQuietly(input);
        }
    }

    private static double duration(Path input, double fallback) {
        Double actual = input == null ? null : FfmpegSupport.durationSeconds(input);
        return actual != null && actual >= 1D ? actual : fallback;
    }

    private static double fallbackDuration(DramaShot shot) {
        return shot == null || shot.getDurationSec() == null || shot.getDurationSec() < 1
                ? DramaContract.DEFAULT_DURATION_SEC
                : shot.getDurationSec();
    }

    private List<DramaShot> listShots(Long episodeId) {
        return shotMapper.selectList(new LambdaQueryWrapper<DramaShot>()
                .eq(DramaShot::getEpisodeId, episodeId)
                .orderByAsc(DramaShot::getShotIndex));
    }

    private DramaShot requireShot(Long id) {
        DramaShot shot = shotMapper.selectById(id);
        if (shot == null || !shot.getUserId().equals(UserContext.requireUserId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "分镜不存在");
        }
        return shot;
    }

    private DramaEpisode requireEpisode(Long id) {
        DramaEpisode episode = episodeMapper.selectById(id);
        if (episode == null || !episode.getUserId().equals(UserContext.requireUserId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "剧集不存在");
        }
        return episode;
    }

    private static String suffix(String storageKey) {
        int dot = storageKey == null ? -1 : storageKey.lastIndexOf('.');
        return dot > 0 ? storageKey.substring(dot) : ".mp4";
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
            // 临时探测文件清理失败不影响字幕返回。
        }
    }
}
