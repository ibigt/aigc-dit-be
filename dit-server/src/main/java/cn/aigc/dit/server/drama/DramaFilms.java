package cn.aigc.dit.server.drama;

import cn.aigc.dit.server.drama.entity.DramaEpisode;
import cn.aigc.dit.server.drama.entity.DramaShot;
import cn.aigc.dit.server.drama.model.resp.DramaFilmResp;
import cn.aigc.dit.server.drama.model.resp.DramaFilmShotResp;
import cn.aigc.dit.server.media.MediaUrls;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 成片指纹与响应：按 {@code shot_index} 拼接 {@code shotId:videoKey:durationSec:dialogue} 再 SHA-256。
 * 时长、对白和衔接方式都纳入指纹，避免改镜长、字幕或从直拼换成顺接后成片页仍显示未过期。
 * 直拼沿用原来的纯哈希，历史成片不必重拼才算未过期。顺接指纹带 {@code smooth:} 前缀。
 * 新成片指纹只对实际入片镜头计算；历史成片无 {@code film_shot_ids} 时仍按全集分镜比对。
 */
public final class DramaFilms {

    static final String SMOOTH_MARK = "smooth:";

    private DramaFilms() {
    }

    public static List<DramaShot> ordered(List<DramaShot> shots) {
        if (shots == null || shots.isEmpty()) {
            return List.of();
        }
        return shots.stream()
                .sorted(Comparator.comparing(DramaShot::getShotIndex, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(DramaShot::getId, Comparator.nullsLast(Long::compareTo)))
                .toList();
    }

    public static List<DramaShot> clips(List<DramaShot> shots) {
        List<DramaShot> clips = new ArrayList<>();
        for (DramaShot shot : ordered(shots)) {
            if (shot != null && StringUtils.hasText(shot.getVideoKey())) {
                clips.add(shot);
            }
        }
        return clips;
    }

    public static List<Integer> missingShotIndexes(List<DramaShot> shots) {
        List<Integer> missing = new ArrayList<>();
        for (DramaShot shot : ordered(shots)) {
            if (shot == null || shot.getShotIndex() == null) {
                continue;
            }
            if (!StringUtils.hasText(shot.getVideoKey())) {
                missing.add(shot.getShotIndex());
            }
        }
        return missing;
    }

    public static String fingerprint(List<DramaShot> shots) {
        return fingerprint(shots, DramaFilmModes.DIRECT);
    }

    public static String fingerprint(List<DramaShot> shots, String mode) {
        String payload = fingerprintPayload(shots);
        if (DramaFilmModes.SMOOTH.equals(DramaFilmModes.normalize(mode))) {
            return SMOOTH_MARK + sha256Hex(payload + "\nmode:" + DramaFilmModes.SMOOTH);
        }
        return sha256Hex(payload);
    }

    /**
     * 从已保存指纹还原衔接方式。没有 {@code smooth:} 前缀的历史哈希视为直拼。
     */
    public static String modeOfFingerprint(String fingerprint) {
        if (fingerprint != null && fingerprint.startsWith(SMOOTH_MARK)) {
            return DramaFilmModes.SMOOTH;
        }
        return DramaFilmModes.DIRECT;
    }

    /** 尚无成片时返回 {@code null}。 */
    public static String assembledMode(DramaEpisode episode) {
        if (episode == null || !StringUtils.hasText(episode.getFilmKey())) {
            return null;
        }
        return modeOfFingerprint(episode.getFilmFingerprint());
    }

    private static String fingerprintPayload(List<DramaShot> shots) {
        StringBuilder payload = new StringBuilder();
        for (DramaShot shot : ordered(shots)) {
            if (payload.length() > 0) {
                payload.append('\n');
            }
            payload.append(shot.getId() == null ? "" : shot.getId());
            payload.append(':');
            payload.append(shot.getVideoKey() == null ? "" : shot.getVideoKey());
            payload.append(':');
            payload.append(shot.getDurationSec() == null ? "" : shot.getDurationSec());
            payload.append(':');
            payload.append(shot.getDialogue() == null ? "" : shot.getDialogue());
        }
        return payload.toString();
    }

    /**
     * 解析入片镜头：有 {@code filmShotIds} 时按该有序列表取镜；若某 id 已删则返回 {@code null}（视为 stale）。
     * 历史成片未记录入片清单时，回退为全集有序分镜（与旧指纹算法一致）。
     */
    public static List<DramaShot> resolveIncludedShots(DramaEpisode episode, List<DramaShot> shots) {
        List<Long> ids = episode == null ? null : episode.getFilmShotIds();
        if (ids == null || ids.isEmpty()) {
            return ordered(shots);
        }
        Map<Long, DramaShot> byId = new HashMap<>();
        for (DramaShot shot : ordered(shots)) {
            if (shot != null && shot.getId() != null) {
                byId.put(shot.getId(), shot);
            }
        }
        List<DramaShot> included = new ArrayList<>(ids.size());
        for (Long id : ids) {
            DramaShot shot = byId.get(id);
            if (shot == null) {
                return null;
            }
            included.add(shot);
        }
        return included;
    }

    public static boolean stale(DramaEpisode episode, List<DramaShot> shots) {
        if (episode == null || !StringUtils.hasText(episode.getFilmKey())) {
            return false;
        }
        List<DramaShot> included = resolveIncludedShots(episode, shots);
        if (included == null) {
            return true;
        }
        return !fingerprint(included, modeOfFingerprint(episode.getFilmFingerprint()))
                .equals(episode.getFilmFingerprint());
    }

    public static DramaFilmResp describe(DramaEpisode episode, List<DramaShot> shots) {
        return describe(episode, shots, null);
    }

    /**
     * @param readyCaptionByShotId when non-null, {@code hasCaptions} means subtitle READY
     *                             and dialogue text matches stored JSON (not merely "has dialogue").
     */
    public static DramaFilmResp describe(DramaEpisode episode, List<DramaShot> shots,
                                         Map<Long, Boolean> readyCaptionByShotId) {
        List<DramaShot> ordered = ordered(shots);
        List<Integer> missing = missingShotIndexes(ordered);
        DramaFilmResp resp = new DramaFilmResp();
        if (episode != null) {
            resp.setEpisodeId(episode.getId());
            resp.setFilmUrl(MediaUrls.of(episode.getFilmKey()));
            resp.setPosterUrl(MediaUrls.of(episode.getFilmPosterKey()));
            resp.setAssembledAt(episode.getFilmAssembledAt());
            resp.setFilmMode(assembledMode(episode));
            if (episode.getFilmShotIds() != null && !episode.getFilmShotIds().isEmpty()) {
                resp.setIncludedShotIds(new ArrayList<>(episode.getFilmShotIds()));
            }
        }
        resp.setStale(stale(episode, ordered));
        resp.setComplete(missing.isEmpty() && !ordered.isEmpty());
        resp.setMissingShotIndexes(missing);
        resp.setShotCount(ordered.size());
        resp.setReadyShotCount(ordered.size() - missing.size());
        boolean hasCaptions = false;
        List<DramaFilmShotResp> items = new ArrayList<>();
        for (DramaShot shot : ordered) {
            DramaFilmShotResp item = new DramaFilmShotResp();
            item.setId(shot.getId());
            item.setShotIndex(shot.getShotIndex());
            item.setTitle(shot.getTitle());
            item.setDurationSec(shot.getDurationSec());
            boolean ready = StringUtils.hasText(shot.getVideoKey());
            item.setReady(ready);
            boolean shotHasCaptions = readyCaptionByShotId != null
                    ? Boolean.TRUE.equals(readyCaptionByShotId.get(shot.getId()))
                    : ready && !DramaStoryboardCompiler.captionLines(shot.getDialogue()).isEmpty();
            item.setHasCaptions(shotHasCaptions);
            hasCaptions = hasCaptions || shotHasCaptions;
            item.setVideoUrl(MediaUrls.of(shot.getVideoKey()));
            item.setVideoPosterUrl(MediaUrls.of(shot.getVideoPosterKey()));
            items.add(item);
        }
        resp.setHasCaptions(hasCaptions);
        resp.setShots(items);
        return resp;
    }

    static String sha256Hex(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
