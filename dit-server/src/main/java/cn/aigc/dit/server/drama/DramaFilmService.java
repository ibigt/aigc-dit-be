package cn.aigc.dit.server.drama;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.generate.ffmpeg.FfmpegSupport;
import cn.aigc.dit.generate.poster.PosterExtractor;
import cn.aigc.dit.server.drama.entity.DramaEpisode;
import cn.aigc.dit.server.drama.entity.DramaShot;
import cn.aigc.dit.server.drama.mapper.DramaEpisodeMapper;
import cn.aigc.dit.server.drama.mapper.DramaShotMapper;
import cn.aigc.dit.server.drama.model.resp.DramaFilmResp;
import cn.aigc.dit.storage.ObjectStorage;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DramaFilmService {

    private final DramaEpisodeMapper episodeMapper;
    private final DramaShotMapper shotMapper;
    private final ObjectStorage objectStorage;
    private final PosterExtractor posterExtractor;
    private final DramaFilmConcat filmConcat;

    public DramaFilmResp get(Long episodeId) {
        DramaEpisode episode = requireEpisode(episodeId);
        return preview(episode);
    }

    public DramaFilmResp preview(DramaEpisode episode) {
        if (episode == null) {
            return DramaFilms.describe(null, List.of());
        }
        return DramaFilms.describe(episode, listShots(episode.getId()));
    }

    public DramaFilmResp assemble(Long episodeId) {
        DramaEpisode episode = requireEpisode(episodeId);
        List<DramaShot> shots = listShots(episode.getId());
        List<DramaShot> clips = DramaFilms.clips(shots);
        if (clips.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "当前剧集还没有已出片镜头，无法拼接成片");
        }
        String filmKey = "users/" + episode.getUserId() + "/drama/" + episode.getId() + "/film.mp4";
        List<Path> temps = new ArrayList<>();
        Path output = null;
        try {
            List<Path> inputs = new ArrayList<>();
            for (DramaShot clip : clips) {
                Path input = FfmpegSupport.tempFile("dit-film-clip-", suffix(clip.getVideoKey()));
                temps.add(input);
                Files.write(input, objectStorage.getBytes(clip.getVideoKey()));
                inputs.add(input);
            }
            output = FfmpegSupport.tempFile("dit-film-out-", ".mp4");
            temps.add(output);
            filmConcat.concat(inputs, output);
            objectStorage.put(filmKey, Files.readAllBytes(output), "video/mp4");
            String posterKey = null;
            try {
                posterKey = posterExtractor.extract(filmKey);
            } catch (Exception e) {
                log.warn("film poster extract failed, episode={}", episode.getId(), e);
            }
            episode.setFilmKey(filmKey);
            episode.setFilmPosterKey(StringUtils.hasText(posterKey) ? posterKey : null);
            episode.setFilmFingerprint(DramaFilms.fingerprint(shots));
            episode.setFilmAssembledAt(OffsetDateTime.now());
            episodeMapper.updateById(episode);
            return DramaFilms.describe(episode, shots);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("assemble film failed, episode={}", episode.getId(), e);
            throw new BizException(ErrorCode.INTERNAL, "拼接成片失败");
        } finally {
            for (Path temp : temps) {
                deleteQuietly(temp);
            }
        }
    }

    private List<DramaShot> listShots(Long episodeId) {
        return shotMapper.selectList(new LambdaQueryWrapper<DramaShot>()
                .eq(DramaShot::getEpisodeId, episodeId)
                .orderByAsc(DramaShot::getShotIndex));
    }

    private DramaEpisode requireEpisode(Long id) {
        DramaEpisode episode = episodeMapper.selectById(id);
        if (episode == null || !episode.getUserId().equals(UserContext.requireUserId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "剧集不存在");
        }
        return episode;
    }

    private static String suffix(String storageKey) {
        if (storageKey == null) {
            return ".mp4";
        }
        int dot = storageKey.lastIndexOf('.');
        return dot > 0 ? storageKey.substring(dot) : ".mp4";
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
            // temp cleanup
        }
    }
}
