package cn.aigc.dit.server.service;

import cn.aigc.dit.server.drama.ArkCharacterReview;
import cn.aigc.dit.server.drama.DramaShotCaptionPostProcessService;
import cn.aigc.dit.server.drama.entity.DramaAsset;
import cn.aigc.dit.server.drama.entity.DramaAssetGeneration;
import cn.aigc.dit.server.drama.entity.DramaEpisode;
import cn.aigc.dit.server.drama.entity.DramaShot;
import cn.aigc.dit.server.drama.entity.DramaShotCaption;
import cn.aigc.dit.server.drama.entity.DramaShotGeneration;
import cn.aigc.dit.server.drama.mapper.DramaAssetGenerationMapper;
import cn.aigc.dit.server.drama.mapper.DramaAssetMapper;
import cn.aigc.dit.server.drama.mapper.DramaEpisodeMapper;
import cn.aigc.dit.server.drama.mapper.DramaShotCaptionMapper;
import cn.aigc.dit.server.drama.mapper.DramaShotGenerationMapper;
import cn.aigc.dit.server.drama.mapper.DramaShotMapper;
import cn.aigc.dit.server.entity.Asset;
import cn.aigc.dit.server.entity.Creation;
import cn.aigc.dit.server.mapper.AssetMapper;
import cn.aigc.dit.server.mapper.CreationMapper;
import cn.aigc.dit.storage.ObjectStorage;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 图片和视频从「我的创作」、短剧、素材库都不再被引用后，删掉对象存储里的文件。
 * 素材库或短剧里还挂着同一文件时保留。
 */
@Slf4j
@Service
public class MediaRetentionService {

    private final ObjectStorage objectStorage;
    private final ArkCharacterReview characterReview;
    private final CreationMapper creationMapper;
    private final AssetMapper assetMapper;
    private final DramaAssetMapper dramaAssetMapper;
    private final DramaAssetGenerationMapper dramaAssetGenerationMapper;
    private final DramaShotMapper dramaShotMapper;
    private final DramaShotGenerationMapper dramaShotGenerationMapper;
    private final DramaShotCaptionMapper dramaShotCaptionMapper;
    private final DramaEpisodeMapper dramaEpisodeMapper;

    public MediaRetentionService(ObjectStorage objectStorage,
                                 ArkCharacterReview characterReview,
                                 CreationMapper creationMapper,
                                 AssetMapper assetMapper,
                                 DramaAssetMapper dramaAssetMapper,
                                 DramaAssetGenerationMapper dramaAssetGenerationMapper,
                                 DramaShotMapper dramaShotMapper,
                                 DramaShotGenerationMapper dramaShotGenerationMapper,
                                 DramaShotCaptionMapper dramaShotCaptionMapper,
                                 DramaEpisodeMapper dramaEpisodeMapper) {
        this.objectStorage = objectStorage;
        this.characterReview = characterReview;
        this.creationMapper = creationMapper;
        this.assetMapper = assetMapper;
        this.dramaAssetMapper = dramaAssetMapper;
        this.dramaAssetGenerationMapper = dramaAssetGenerationMapper;
        this.dramaShotMapper = dramaShotMapper;
        this.dramaShotGenerationMapper = dramaShotGenerationMapper;
        this.dramaShotCaptionMapper = dramaShotCaptionMapper;
        this.dramaEpisodeMapper = dramaEpisodeMapper;
    }

    public List<String> collectSeriesMediaKeys(Long userId, Long seriesId) {
        if (userId == null || seriesId == null) {
            return List.of();
        }
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        List<DramaAsset> assets = orEmpty(dramaAssetMapper.selectList(new LambdaQueryWrapper<DramaAsset>()
                .eq(DramaAsset::getUserId, userId)
                .eq(DramaAsset::getSeriesId, seriesId)));
        List<Long> assetIds = new ArrayList<>();
        for (DramaAsset asset : assets) {
            if (asset == null) {
                continue;
            }
            if (asset.getId() != null) {
                assetIds.add(asset.getId());
            }
            addKey(keys, asset.getImageStorageKey());
            addKey(keys, asset.getVoiceReferenceKey());
        }
        if (!assetIds.isEmpty()) {
            for (DramaAssetGeneration generation : orEmpty(dramaAssetGenerationMapper.selectList(
                    new LambdaQueryWrapper<DramaAssetGeneration>().in(DramaAssetGeneration::getAssetId, assetIds)))) {
                if (generation != null) {
                    addKey(keys, generation.getStorageKey());
                }
            }
        }
        List<DramaShot> shots = orEmpty(dramaShotMapper.selectList(new LambdaQueryWrapper<DramaShot>()
                .eq(DramaShot::getUserId, userId)
                .eq(DramaShot::getSeriesId, seriesId)));
        addShotMedia(keys, userId, shots);
        for (DramaEpisode episode : orEmpty(dramaEpisodeMapper.selectList(new LambdaQueryWrapper<DramaEpisode>()
                .eq(DramaEpisode::getUserId, userId)
                .eq(DramaEpisode::getSeriesId, seriesId)))) {
            addFilm(keys, episode);
        }
        addDramaCreationKeys(keys, userId, null, seriesId);
        return List.copyOf(keys);
    }

    public List<String> collectEpisodeMediaKeys(Long userId, Long episodeId) {
        if (userId == null || episodeId == null) {
            return List.of();
        }
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        List<DramaAsset> assets = orEmpty(dramaAssetMapper.selectList(new LambdaQueryWrapper<DramaAsset>()
                .eq(DramaAsset::getUserId, userId)
                .eq(DramaAsset::getEpisodeId, episodeId)));
        List<Long> assetIds = new ArrayList<>();
        for (DramaAsset asset : assets) {
            if (asset == null) {
                continue;
            }
            if (asset.getId() != null) {
                assetIds.add(asset.getId());
            }
            addKey(keys, asset.getImageStorageKey());
            addKey(keys, asset.getVoiceReferenceKey());
        }
        if (!assetIds.isEmpty()) {
            for (DramaAssetGeneration generation : orEmpty(dramaAssetGenerationMapper.selectList(
                    new LambdaQueryWrapper<DramaAssetGeneration>().in(DramaAssetGeneration::getAssetId, assetIds)))) {
                if (generation != null) {
                    addKey(keys, generation.getStorageKey());
                }
            }
        }
        List<DramaShot> shots = orEmpty(dramaShotMapper.selectList(new LambdaQueryWrapper<DramaShot>()
                .eq(DramaShot::getUserId, userId)
                .eq(DramaShot::getEpisodeId, episodeId)));
        addShotMedia(keys, userId, shots);
        addFilm(keys, dramaEpisodeMapper.selectById(episodeId));
        addDramaCreationKeys(keys, userId, List.copyOf(keys), null);
        return List.copyOf(keys);
    }

    public List<String> withCaptionKeys(Long userId, Long shotId, Collection<String> keys) {
        LinkedHashSet<String> all = new LinkedHashSet<>();
        if (keys != null) {
            keys.forEach(key -> addKey(all, key));
        }
        if (userId != null && shotId != null) {
            addCaptionKeys(all, orEmpty(dramaShotCaptionMapper.selectList(new LambdaQueryWrapper<DramaShotCaption>()
                    .eq(DramaShotCaption::getUserId, userId)
                    .eq(DramaShotCaption::getShotId, shotId))));
        }
        return List.copyOf(all);
    }

    public void purgeAfterCommit(Long userId, Collection<String> keys) {
        List<String> distinct = distinctKeys(keys);
        if (userId == null || distinct.isEmpty()) {
            return;
        }
        Runnable purge = () -> purgeNow(userId, distinct);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            purge.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                purge.run();
            }
        });
    }

    private void purgeNow(Long userId, List<String> keys) {
        Set<String> used = referencedKeys(userId, keys);
        Set<String> dramaImages = dramaImageKeys(userId, keys);
        for (String key : keys) {
            if (!dramaImages.contains(key) && isImage(key)) {
                characterReview.release(key);
            }
            if (used.contains(key)) {
                continue;
            }
            deleteObject(key);
            if (isVideo(key)) {
                deleteObject(captionVtt(key));
                deleteObject(legacyCaptionVtt(key));
            }
        }
    }

    private void addShotMedia(LinkedHashSet<String> keys, Long userId, List<DramaShot> shots) {
        List<Long> shotIds = new ArrayList<>();
        for (DramaShot shot : shots) {
            if (shot == null) {
                continue;
            }
            if (shot.getId() != null) {
                shotIds.add(shot.getId());
            }
            addShotFiles(keys, shot);
        }
        if (shotIds.isEmpty()) {
            return;
        }
        for (DramaShotGeneration generation : orEmpty(dramaShotGenerationMapper.selectList(
                new LambdaQueryWrapper<DramaShotGeneration>().in(DramaShotGeneration::getShotId, shotIds)))) {
            addGenerationFiles(keys, generation);
        }
        addCaptionKeys(keys, orEmpty(dramaShotCaptionMapper.selectList(new LambdaQueryWrapper<DramaShotCaption>()
                .eq(DramaShotCaption::getUserId, userId)
                .in(DramaShotCaption::getShotId, shotIds))));
    }

    private void addDramaCreationKeys(LinkedHashSet<String> keys, Long userId, Collection<String> storageKeys, Long seriesId) {
        LambdaQueryWrapper<Creation> wrapper = new LambdaQueryWrapper<Creation>()
                .eq(Creation::getUserId, userId)
                .eq(Creation::getProjectKind, "drama");
        if (seriesId != null) {
            wrapper.eq(Creation::getProjectId, seriesId);
        } else if (storageKeys != null && !storageKeys.isEmpty()) {
            wrapper.in(Creation::getStorageKey, storageKeys);
        } else {
            return;
        }
        for (Creation creation : orEmpty(creationMapper.selectList(wrapper))) {
            if (creation == null) {
                continue;
            }
            addKey(keys, creation.getStorageKey());
            addKey(keys, creation.getPosterKey());
        }
    }

    private Set<String> referencedKeys(Long userId, Collection<String> keys) {
        Set<String> hit = new LinkedHashSet<>();
        List<String> list = distinctKeys(keys);
        if (userId == null || list.isEmpty()) {
            return hit;
        }
        for (Creation creation : orEmpty(creationMapper.selectList(new LambdaQueryWrapper<Creation>()
                .eq(Creation::getUserId, userId)
                .and(query -> query.in(Creation::getStorageKey, list).or().in(Creation::getPosterKey, list))))) {
            keep(hit, list, creation == null ? null : creation.getStorageKey(), creation == null ? null : creation.getPosterKey());
        }
        for (Asset asset : orEmpty(assetMapper.selectList(new LambdaQueryWrapper<Asset>()
                .eq(Asset::getUserId, userId)
                .and(query -> query.in(Asset::getStorageKey, list).or().in(Asset::getPosterKey, list))))) {
            keep(hit, list, asset == null ? null : asset.getStorageKey(), asset == null ? null : asset.getPosterKey());
        }
        for (DramaAsset asset : orEmpty(dramaAssetMapper.selectList(new LambdaQueryWrapper<DramaAsset>()
                .eq(DramaAsset::getUserId, userId)
                .and(query -> query.in(DramaAsset::getImageStorageKey, list).or().in(DramaAsset::getVoiceReferenceKey, list))))) {
            keep(hit, list, asset == null ? null : asset.getImageStorageKey(), asset == null ? null : asset.getVoiceReferenceKey());
        }
        for (DramaAssetGeneration generation : orEmpty(dramaAssetGenerationMapper.selectList(new LambdaQueryWrapper<DramaAssetGeneration>()
                .eq(DramaAssetGeneration::getUserId, userId)
                .in(DramaAssetGeneration::getStorageKey, list)))) {
            keep(hit, list, generation == null ? null : generation.getStorageKey());
        }
        for (DramaShot shot : orEmpty(dramaShotMapper.selectList(new LambdaQueryWrapper<DramaShot>()
                .eq(DramaShot::getUserId, userId)
                .and(query -> query.in(DramaShot::getVideoKey, list)
                        .or().in(DramaShot::getVideoPosterKey, list)
                        .or().in(DramaShot::getVideoTailKey, list)
                        .or().in(DramaShot::getFirstFrameKey, list)
                        .or().in(DramaShot::getEndFrameKey, list)
                        .or().in(DramaShot::getChainedFromTailKey, list))))) {
            if (shot != null) {
                keep(hit, list, shot.getVideoKey(), shot.getVideoPosterKey(), shot.getVideoTailKey(),
                        shot.getFirstFrameKey(), shot.getEndFrameKey(), shot.getChainedFromTailKey());
            }
        }
        for (DramaShotGeneration generation : orEmpty(dramaShotGenerationMapper.selectList(new LambdaQueryWrapper<DramaShotGeneration>()
                .eq(DramaShotGeneration::getUserId, userId)
                .and(query -> query.in(DramaShotGeneration::getVideoKey, list)
                        .or().in(DramaShotGeneration::getVideoPosterKey, list)
                        .or().in(DramaShotGeneration::getVideoTailKey, list))))) {
            if (generation != null) {
                keep(hit, list, generation.getVideoKey(), generation.getVideoPosterKey(), generation.getVideoTailKey());
            }
        }
        for (DramaShotCaption caption : orEmpty(dramaShotCaptionMapper.selectList(new LambdaQueryWrapper<DramaShotCaption>()
                .eq(DramaShotCaption::getUserId, userId)
                .and(query -> query.in(DramaShotCaption::getSourceVideoKey, list)
                        .or().in(DramaShotCaption::getAudioKey, list)
                        .or().in(DramaShotCaption::getSubtitleJsonKey, list)
                        .or().in(DramaShotCaption::getCaptionVideoKey, list))))) {
            if (caption != null) {
                keep(hit, list, caption.getSourceVideoKey(), caption.getAudioKey(),
                        caption.getSubtitleJsonKey(), caption.getCaptionVideoKey());
            }
        }
        for (DramaEpisode episode : orEmpty(dramaEpisodeMapper.selectList(new LambdaQueryWrapper<DramaEpisode>()
                .eq(DramaEpisode::getUserId, userId)
                .and(query -> query.in(DramaEpisode::getFilmKey, list).or().in(DramaEpisode::getFilmPosterKey, list))))) {
            if (episode != null) {
                keep(hit, list, episode.getFilmKey(), episode.getFilmPosterKey());
            }
        }
        return hit;
    }

    private Set<String> dramaImageKeys(Long userId, Collection<String> keys) {
        Set<String> hit = new LinkedHashSet<>();
        List<String> list = distinctKeys(keys);
        if (userId == null || list.isEmpty()) {
            return hit;
        }
        for (DramaAsset asset : orEmpty(dramaAssetMapper.selectList(new LambdaQueryWrapper<DramaAsset>()
                .eq(DramaAsset::getUserId, userId)
                .in(DramaAsset::getImageStorageKey, list)))) {
            keep(hit, list, asset == null ? null : asset.getImageStorageKey());
        }
        for (DramaAssetGeneration generation : orEmpty(dramaAssetGenerationMapper.selectList(new LambdaQueryWrapper<DramaAssetGeneration>()
                .eq(DramaAssetGeneration::getUserId, userId)
                .in(DramaAssetGeneration::getStorageKey, list)))) {
            keep(hit, list, generation == null ? null : generation.getStorageKey());
        }
        return hit;
    }

    private static void addShotFiles(Set<String> keys, DramaShot shot) {
        addKey(keys, shot.getVideoKey());
        addKey(keys, shot.getVideoPosterKey());
        addKey(keys, shot.getVideoTailKey());
        addKey(keys, shot.getFirstFrameKey());
        addKey(keys, shot.getEndFrameKey());
    }

    private static void addGenerationFiles(Set<String> keys, DramaShotGeneration generation) {
        if (generation == null) {
            return;
        }
        addKey(keys, generation.getVideoKey());
        addKey(keys, generation.getVideoPosterKey());
        addKey(keys, generation.getVideoTailKey());
    }

    private static void addCaptionKeys(Set<String> keys, List<DramaShotCaption> captions) {
        for (DramaShotCaption caption : captions) {
            if (caption == null) {
                continue;
            }
            addKey(keys, caption.getSourceVideoKey());
            addKey(keys, caption.getAudioKey());
            addKey(keys, caption.getSubtitleJsonKey());
            addKey(keys, caption.getCaptionVideoKey());
        }
    }

    private static void addFilm(Set<String> keys, DramaEpisode episode) {
        if (episode == null) {
            return;
        }
        addKey(keys, episode.getFilmKey());
        addKey(keys, episode.getFilmPosterKey());
        if (StringUtils.hasText(episode.getFilmKey())) {
            addKey(keys, captionVtt(episode.getFilmKey()));
            addKey(keys, legacyCaptionVtt(episode.getFilmKey()));
        }
    }

    private static List<String> distinctKeys(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (String key : keys) {
            addKey(distinct, key);
        }
        return List.copyOf(distinct);
    }

    private static void addKey(Set<String> keys, String key) {
        if (StringUtils.hasText(key)) {
            keys.add(key);
        }
    }

    private static void keep(Set<String> hit, Collection<String> wanted, String... values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (StringUtils.hasText(value) && wanted.contains(value)) {
                hit.add(value);
            }
        }
    }

    private void deleteObject(String key) {
        if (!StringUtils.hasText(key)) {
            return;
        }
        try {
            objectStorage.delete(key);
        } catch (RuntimeException e) {
            log.warn("delete unreferenced media failed, key={}", key, e);
        }
    }

    private static boolean isImage(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".webp") || lower.endsWith(".gif");
    }

    private static boolean isVideo(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".webm");
    }

    private static String captionVtt(String filmKey) {
        return captionBase(filmKey) + "-captions-" + DramaShotCaptionPostProcessService.PROCESSOR_VERSION + ".vtt";
    }

    private static String legacyCaptionVtt(String filmKey) {
        return captionBase(filmKey) + "-captions.vtt";
    }

    private static String captionBase(String filmKey) {
        int dot = filmKey.lastIndexOf('.');
        return dot > 0 ? filmKey.substring(0, dot) : filmKey;
    }

    private static <T> List<T> orEmpty(List<T> rows) {
        return rows == null ? List.of() : rows;
    }
}
