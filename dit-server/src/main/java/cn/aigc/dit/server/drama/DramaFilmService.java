package cn.aigc.dit.server.drama;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.generate.ffmpeg.FfmpegSupport;
import cn.aigc.dit.generate.poster.PosterExtractor;
import cn.aigc.dit.server.service.CreationService;
import cn.aigc.dit.server.drama.entity.DramaEpisode;
import cn.aigc.dit.server.drama.entity.DramaSeries;
import cn.aigc.dit.server.drama.entity.DramaShot;
import cn.aigc.dit.server.drama.mapper.DramaEpisodeMapper;
import cn.aigc.dit.server.drama.mapper.DramaSeriesMapper;
import cn.aigc.dit.server.drama.mapper.DramaShotMapper;
import cn.aigc.dit.server.drama.model.resp.DramaFilmResp;
import cn.aigc.dit.server.media.MediaUrls;
import cn.aigc.dit.storage.ObjectStorage;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
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
    private final DramaSeriesMapper seriesMapper;
    private final ObjectStorage objectStorage;
    private final PosterExtractor posterExtractor;
    private final DramaFilmConcat filmConcat;
    private final DramaCaptionService captionService;
    private final CreationService creationService;
    private final ObjectProvider<DramaShotCaptionPostProcessService> captionPostProcess;

    public DramaFilmResp get(Long episodeId) {
        DramaEpisode episode = requireEpisode(episodeId);
        return preview(episode);
    }

    public DramaFilmResp preview(DramaEpisode episode) {
        if (episode == null) {
            return DramaFilms.describe(null, List.of());
        }
        List<DramaShot> shots = listShots(episode.getId());
        return DramaFilms.describe(episode, shots, captionService.readyCaptionFlags(shots));
    }

    public DramaFilmResp assemble(Long episodeId) {
        return assemble(episodeId, null, null);
    }

    public DramaFilmResp assemble(Long episodeId, List<Long> shotIds) {
        return assemble(episodeId, shotIds, null);
    }

    public DramaFilmResp assemble(Long episodeId, List<Long> shotIds, String mode) {
        DramaEpisode episode = requireEpisode(episodeId);
        List<DramaShot> shots = listShots(episode.getId());
        List<DramaShot> clips = resolveAssembleClips(shots, shotIds);
        if (clips.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "当前剧集还没有已出片镜头，无法拼接成片");
        }
        String filmMode = DramaFilmModes.normalize(mode);
        String filmKey = "users/" + episode.getUserId() + "/drama/" + episode.getId() + "/film.mp4";
        List<Path> temps = new ArrayList<>();
        Path output = null;
        try {
            List<Path> inputs = materializeClips(clips, temps, "dit-film-clip-");
            output = FfmpegSupport.tempFile("dit-film-out-", ".mp4");
            temps.add(output);
            List<DramaFilmModes.ClipSpan> spans = concatClips(filmMode, aspectRatio(episode), clips, inputs, output);
            objectStorage.put(filmKey, Files.readAllBytes(output), "video/mp4");
            captionService.storeJoinedEpisodeVtt(filmKey, clips, spans);
            String posterKey = null;
            try {
                posterKey = posterExtractor.extract(filmKey);
            } catch (Exception e) {
                log.warn("film poster extract failed, episode={}", episode.getId(), e);
            }
            // 软成片固定写 film.mp4，put 覆盖原文件。上一份带字导出对不上这次衔接，删掉，只留当前成片。
            deleteReplacedFilmOutputs(episode, filmKey, posterKey);
            episode.setFilmKey(filmKey);
            episode.setFilmPosterKey(StringUtils.hasText(posterKey) ? posterKey : null);
            episode.setFilmFingerprint(DramaFilms.fingerprint(clips, filmMode));
            episode.setFilmShotIds(clips.stream().map(DramaShot::getId).toList());
            episode.setFilmAssembledAt(OffsetDateTime.now());
            episodeMapper.updateById(episode);
            try {
                creationService.recordDramaFilm(episode);
            } catch (Exception recorded) {
                log.warn("record drama film into creations failed, episode={}", episode.getId(), recorded);
            }
            return DramaFilms.describe(episode, shots, captionService.readyCaptionFlags(shots));
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
     * Assemble a hard-burned film by burning each clip's current cues (on demand) then concatenating.
     * Returns the burned film object key URL via a one-off storage key (does not replace soft film).
     */
    public DramaFilmResp assembleBurned(Long episodeId, List<Long> shotIds) {
        return assembleBurned(episodeId, shotIds, null);
    }

    public DramaFilmResp assembleBurned(Long episodeId, List<Long> shotIds, String mode) {
        DramaShotCaptionPostProcessService postProcess = captionPostProcess.getIfAvailable();
        if (postProcess == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "字幕后处理未启用，无法导出带字成片");
        }
        DramaEpisode episode = requireEpisode(episodeId);
        List<DramaShot> shots = listShots(episode.getId());
        // 未显式传 shotIds 时，优先沿用最近一次软成片入片清单，避免把未勾选镜头又拼进带字导出。
        List<Long> effectiveShotIds = shotIds;
        if (effectiveShotIds == null
                && episode.getFilmShotIds() != null
                && !episode.getFilmShotIds().isEmpty()) {
            effectiveShotIds = episode.getFilmShotIds();
        }
        List<DramaShot> clips = resolveAssembleClips(shots, effectiveShotIds);
        if (clips.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "当前剧集还没有已出片镜头，无法导出带字成片");
        }
        String filmMode = StringUtils.hasText(mode)
                ? DramaFilmModes.normalize(mode)
                : burnedMode(episode);
        String filmKey = burnedFilmKey(episode.getUserId(), episode.getId());
        List<Path> temps = new ArrayList<>();
        try {
            List<Path> inputs = new ArrayList<>();
            for (DramaShot clip : clips) {
                String videoKey = clip.getVideoKey();
                try {
                    var caption = postProcess.findForSource(clip.getId(), clip.getVideoKey());
                    if (caption != null && "READY".equals(caption.getStatus())) {
                        caption = postProcess.burnOwned(clip.getId());
                        if (StringUtils.hasText(caption.getCaptionVideoKey())) {
                            videoKey = caption.getCaptionVideoKey();
                        }
                    }
                } catch (BizException e) {
                    log.warn("burn clip for film export skipped shot={} reason={}", clip.getId(), e.getMessage());
                }
                Path input = FfmpegSupport.tempFile("dit-film-burn-", suffix(videoKey));
                temps.add(input);
                Files.write(input, objectStorage.getBytes(videoKey));
                inputs.add(input);
            }
            Path output = FfmpegSupport.tempFile("dit-film-burn-out-", ".mp4");
            temps.add(output);
            concatClips(filmMode, aspectRatio(episode), clips, inputs, output);
            objectStorage.put(filmKey, Files.readAllBytes(output), "video/mp4");
            DramaFilmResp resp = DramaFilms.describe(episode, shots, captionService.readyCaptionFlags(shots));
            resp.setFilmUrl(MediaUrls.of(filmKey));
            return resp;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("assemble burned film failed, episode={}", episodeId, e);
            throw new BizException(ErrorCode.INTERNAL, "导出带字成片失败");
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
        deleteObjectQuietly(burnedFilmKey(episode.getUserId(), episode.getId()));
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

    private List<Path> materializeClips(List<DramaShot> clips, List<Path> temps, String prefix) throws Exception {
        List<Path> inputs = new ArrayList<>();
        for (DramaShot clip : clips) {
            Path input = FfmpegSupport.tempFile(prefix, suffix(clip.getVideoKey()));
            temps.add(input);
            Files.write(input, objectStorage.getBytes(clip.getVideoKey()));
            inputs.add(input);
        }
        return inputs;
    }

    /**
     * 直拼走流复制；顺接与带字导出共用同一套裁切窗，避免成片和字幕两条时间轴。
     */
    private List<DramaFilmModes.ClipSpan> concatClips(String mode, String aspectRatio, List<DramaShot> clips,
                                                       List<Path> inputs, Path output) {
        List<Double> durations = new ArrayList<>();
        List<List<DramaFilmModes.CueWindow>> windows = new ArrayList<>();
        for (int i = 0; i < clips.size(); i++) {
            double duration = DramaFilmModes.durationOf(clips.get(i), FfmpegSupport.durationSeconds(inputs.get(i)));
            durations.add(duration);
            List<DramaFilmModes.CueWindow> speech = captionService.speechWindows(clips.get(i), duration);
            windows.add(speech == null ? List.of() : speech);
        }
        List<DramaFilmModes.ClipSpan> spans = DramaFilmModes.plan(mode, clips, durations, windows);
        if (DramaFilmModes.direct(mode)) {
            filmConcat.concat(inputs, output);
        } else {
            filmConcat.concatSmooth(inputs, spans, aspectRatio, output);
        }
        return spans;
    }

    private String aspectRatio(DramaEpisode episode) {
        if (episode == null || episode.getSeriesId() == null) {
            return DramaContract.DEFAULT_ASPECT_RATIO;
        }
        DramaSeries series = seriesMapper.selectById(episode.getSeriesId());
        if (series == null || !StringUtils.hasText(series.getAspectRatio())) {
            return DramaContract.DEFAULT_ASPECT_RATIO;
        }
        String ratio = series.getAspectRatio().trim().replace('：', ':');
        return DramaContract.ASPECT_RATIOS.contains(ratio) ? ratio : DramaContract.DEFAULT_ASPECT_RATIO;
    }

    /** 带字成片与软成片分开存放。重新拼接后删掉它，避免导出仍拿到上一种衔接方式的视频。 */
    static String burnedFilmKey(Long userId, Long episodeId) {
        return "users/" + userId + "/drama/" + episodeId + "/film-burned-"
                + DramaShotCaptionPostProcessService.PROCESSOR_VERSION + ".mp4";
    }

    /**
     * 新成片已经写入后，清掉被换掉的旧文件。同一把 film.mp4 由 put 覆盖，这里不删它。
     * 键变了的旧成片、旧封面，以及上一份带字导出，都删掉。
     */
    private void deleteReplacedFilmOutputs(DramaEpisode episode, String newFilmKey, String newPosterKey) {
        if (episode == null) {
            return;
        }
        String previousFilm = episode.getFilmKey();
        if (StringUtils.hasText(previousFilm) && !previousFilm.equals(newFilmKey)) {
            deleteObjectQuietly(previousFilm);
            captionService.deleteEpisodeVtt(previousFilm);
        }
        String previousPoster = episode.getFilmPosterKey();
        if (StringUtils.hasText(previousPoster) && !previousPoster.equals(newPosterKey)) {
            deleteObjectQuietly(previousPoster);
        }
        deleteObjectQuietly(burnedFilmKey(episode.getUserId(), episode.getId()));
    }

    /** 带字导出未指定模式时沿用软字幕成片的衔接，避免两条时间轴。 */
    private static String burnedMode(DramaEpisode episode) {
        if (episode != null && StringUtils.hasText(episode.getFilmFingerprint())) {
            return DramaFilms.modeOfFingerprint(episode.getFilmFingerprint());
        }
        return DramaFilmModes.SMOOTH;
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
