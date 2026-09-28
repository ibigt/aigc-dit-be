package cn.aigc.dit.server.drama;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.generate.ffmpeg.FfmpegSupport;
import cn.aigc.dit.generate.poster.PosterExtractor;
import cn.aigc.dit.server.service.CreationService;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class DramaFilmService {

    private final DramaEpisodeMapper episodeMapper;
    private final DramaShotMapper shotMapper;
    private final ObjectStorage objectStorage;
    private final PosterExtractor posterExtractor;
    private final DramaFilmConcat filmConcat;
    private final DramaCaptionService captionService;
    private final CreationService creationService;

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
        return assemble(episodeId, null);
    }

    public DramaFilmResp assemble(Long episodeId, List<Long> shotIds) {
        DramaEpisode episode = requireEpisode(episodeId);
        List<DramaShot> shots = listShots(episode.getId());
        List<DramaShot> clips = resolveAssembleClips(shots, shotIds);
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
            captionService.storeEpisodeVtt(filmKey, clips, inputs);
            String posterKey = null;
            try {
                posterKey = posterExtractor.extract(filmKey);
            } catch (Exception e) {
                log.warn("film poster extract failed, episode={}", episode.getId(), e);
            }
            episode.setFilmKey(filmKey);
            episode.setFilmPosterKey(StringUtils.hasText(posterKey) ? posterKey : null);
            episode.setFilmFingerprint(DramaFilms.fingerprint(clips));
            episode.setFilmShotIds(clips.stream().map(DramaShot::getId).toList());
            episode.setFilmAssembledAt(OffsetDateTime.now());
            episodeMapper.updateById(episode);
            try {
                creationService.recordDramaFilm(episode);
            } catch (Exception recorded) {
                log.warn("record drama film into creations failed, episode={}", episode.getId(), recorded);
            }
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

    /**
     * 分镜增删改切后作废已拼接成片：删对象存储文件，并清空 episode 上的成片字段。
     */
    @Transactional
    public void discardAssembledFilm(Long episodeId) {
        if (episodeId == null) {
            return;
        }
        DramaEpisode episode = episodeMapper.selectById(episodeId);
        if (episode == null) {
            return;
        }
        boolean hadFilm = StringUtils.hasText(episode.getFilmKey())
                || StringUtils.hasText(episode.getFilmPosterKey())
                || StringUtils.hasText(episode.getFilmFingerprint())
                || (episode.getFilmShotIds() != null && !episode.getFilmShotIds().isEmpty());
        if (!hadFilm) {
            return;
        }
        creationService.removeDramaFilm(episode.getUserId(), episode.getFilmKey());
        deleteObjectQuietly(episode.getFilmKey());
        deleteObjectQuietly(episode.getFilmPosterKey());
        captionService.deleteEpisodeVtt(episode.getFilmKey());
        episode.setFilmKey(null);
        episode.setFilmPosterKey(null);
        episode.setFilmFingerprint(null);
        episode.setFilmShotIds(null);
        episode.setFilmAssembledAt(null);
        episodeMapper.updateById(episode);
        log.info("discarded assembled film for episode {}", episodeId);
    }

    /**
     * shotIds 为 null：兼容旧客户端，拼本集全部已出片。
     * shotIds 非 null：必须按勾选入片；空列表直接拒绝，避免「空列表回退全集」误拼。
     */
    static List<DramaShot> resolveAssembleClips(List<DramaShot> shots, List<Long> shotIds) {
        if (shotIds == null) {
            return DramaFilms.clips(shots);
        }
        if (shotIds.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请至少选择一个已出片镜头再拼接");
        }
        Map<Long, DramaShot> byId = new HashMap<>();
        for (DramaShot shot : shots) {
            if (shot != null && shot.getId() != null) {
                byId.putIfAbsent(shot.getId(), shot);
            }
        }
        Set<Long> unique = new LinkedHashSet<>();
        for (Long id : shotIds) {
            if (id == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "分镜 id 无效");
            }
            unique.add(id);
        }
        List<DramaShot> selected = new ArrayList<>(unique.size());
        for (Long id : unique) {
            DramaShot shot = byId.get(id);
            if (shot == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "分镜不属于本集或不存在");
            }
            if (!StringUtils.hasText(shot.getVideoKey())) {
                throw new BizException(ErrorCode.BAD_REQUEST, "分镜尚未出片，无法入片");
            }
            selected.add(shot);
        }
        return DramaFilms.ordered(selected);
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

    private void deleteObjectQuietly(String storageKey) {
        if (!StringUtils.hasText(storageKey)) {
            return;
        }
        try {
            objectStorage.delete(storageKey);
        } catch (Exception e) {
            log.warn("delete film object failed, key={}", storageKey, e);
        }
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
