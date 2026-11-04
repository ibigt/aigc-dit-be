package cn.aigc.dit.server.service;

import cn.aigc.dit.common.constant.AssetKind;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.generate.poster.PosterExtractor;
import cn.aigc.dit.server.credit.VideoTiers;
import cn.aigc.dit.server.entity.Asset;
import cn.aigc.dit.server.entity.Creation;
import cn.aigc.dit.server.drama.entity.DramaAsset;
import cn.aigc.dit.server.drama.mapper.DramaAssetMapper;
import cn.aigc.dit.server.mapper.AssetMapper;
import cn.aigc.dit.server.mapper.CreationMapper;
import cn.aigc.dit.server.media.MediaUrls;
import cn.aigc.dit.server.media.AssetUploadPolicy;
import cn.aigc.dit.server.media.AssetMetadataProbe;
import cn.aigc.dit.server.model.resp.AssetResp;
import cn.aigc.dit.storage.ObjectStorage;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AssetService extends ServiceImpl<AssetMapper, Asset> {

    private final ObjectStorage objectStorage;
    private final PosterExtractor posterExtractor;
    private final CreationMapper creationMapper;
    private final DramaAssetMapper dramaAssetMapper;

    public List<AssetResp> listMine(String kind, String query) {
        return listMine(kind, query, null);
    }

    public List<AssetResp> listMine(String kind, String query, String orderBy) {
        Long userId = UserContext.requireUserId();
        LambdaQueryWrapper<Asset> wrapper = new LambdaQueryWrapper<Asset>()
                .eq(Asset::getUserId, userId)
                .eq(StringUtils.hasText(kind) && !"all".equalsIgnoreCase(kind), Asset::getKind, kind)
                .like(StringUtils.hasText(query), Asset::getName, query);
        applyOrder(wrapper, orderBy);
        return list(wrapper).stream().map(this::toResp).toList();
    }

    @Transactional
    public AssetResp upload(MultipartFile file) {
        return upload(file, null);
    }

    @Transactional
    public AssetResp upload(MultipartFile file, String name) {
        Long userId = UserContext.requireUserId();
        AssetUploadPolicy.Validated validated = AssetUploadPolicy.validate(file);
        String mime = validated.mimeType();
        String storedName = assetName(name, file == null ? null : file.getOriginalFilename());
        String key = putOwnedFile(file, "assets");
        String kind = validated.kind();
        String posterKey = AssetKind.VIDEO.equals(kind) ? posterExtractor.extract(key)
                : AssetKind.AUDIO.equals(kind) ? null : key;
        Asset asset = new Asset();
        asset.setUserId(userId);
        asset.setName(storedName);
        asset.setMimeType(mime);
        asset.setKind(kind);
        asset.setStorageKey(key);
        asset.setPosterKey(posterKey);
        asset.setSizeBytes(file.getSize());
        applyMetadata(asset, file, kind);
        save(asset);
        return toResp(asset);
    }

    /**
     * 把文件写入当前用户目录，不创建素材库记录。短剧参考图走这条路径。
     */
    public String putOwnedFile(MultipartFile file, String folder) {
        AssetUploadPolicy.Validated validated = AssetUploadPolicy.validate(file);
        String mime = validated.mimeType();
        Long userId = UserContext.requireUserId();
        String ext = validated.dottedExtension();
        String key = "users/" + userId + "/" + sanitizeFolder(folder) + "/" + UUID.randomUUID() + ext;
        try {
            objectStorage.put(key, file.getBytes(), mime);
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL, "读取上传文件失败");
        }
        return key;
    }

    /** 对象属于当前用户目录即可，不必已入素材库。 */
    public void assertOwnedStorageKey(String storageKey) {
        if (!StringUtils.hasText(storageKey) || storageKey.contains("..")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "非法对象路径");
        }
        Long userId = UserContext.requireUserId();
        if (!storageKey.startsWith("users/" + userId + "/")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "参考素材不属于当前用户");
        }
    }

    public Asset requireOwned(Long assetId) {
        Asset asset = getById(assetId);
        if (asset == null || !asset.getUserId().equals(UserContext.requireUserId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "素材不存在");
        }
        return asset;
    }

    public Asset requireOwnedKey(String storageKey) {
        Long userId = UserContext.requireUserId();
        Asset asset = getOne(new LambdaQueryWrapper<Asset>()
                .eq(Asset::getUserId, userId)
                .eq(Asset::getStorageKey, storageKey)
                .last("LIMIT 1"));
        if (asset == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "参考素材不属于当前用户");
        }
        return asset;
    }

    /**
     * 按对象存储 key 取本人素材的实测时长（毫秒）；没有素材行或未探测出时长时返回 null。
     * 参考视频计费以此为准，不信前端上报的秒数。
     */
    public Long findOwnedDurationMs(String storageKey) {
        if (!StringUtils.hasText(storageKey)) {
            return null;
        }
        Long userId = UserContext.requireUserId();
        Asset asset = getOne(new LambdaQueryWrapper<Asset>()
                .select(Asset::getDurationMs)
                .eq(Asset::getUserId, userId)
                .eq(Asset::getStorageKey, storageKey)
                .last("LIMIT 1"));
        return asset == null ? null : asset.getDurationMs();
    }

    /**
     * 参考视频（视频输入）的分辨率档（480P/720P/…）：优先用创作产物的分辨率档位
     * （工作流里上游生成的视频走这条，creation.resolution 记的是生成时选的那档），
     * 其次用素材行的宽高按短边折档（素材库上传的视频）。都查不到时返回 null，
     * 由调用方按保守口径（模型最高档）兜底。
     */
    public String findOwnedVideoResolution(Long userId, String storageKey) {
        if (!StringUtils.hasText(storageKey)) {
            return null;
        }
        LambdaQueryWrapper<Creation> creationQuery = new LambdaQueryWrapper<Creation>()
                .select(Creation::getResolution)
                .eq(Creation::getStorageKey, storageKey)
                .last("LIMIT 1");
        if (userId != null) {
            creationQuery.eq(Creation::getUserId, userId);
        }
        Creation creation = creationMapper.selectOne(creationQuery);
        if (creation != null) {
            String tier = VideoTiers.normalize(creation.getResolution());
            if (tier != null) {
                return tier;
            }
        }
        LambdaQueryWrapper<Asset> assetQuery = new LambdaQueryWrapper<Asset>()
                .select(Asset::getWidth, Asset::getHeight)
                .eq(Asset::getStorageKey, storageKey)
                .last("LIMIT 1");
        if (userId != null) {
            assetQuery.eq(Asset::getUserId, userId);
        }
        Asset asset = getOne(assetQuery);
        if (asset != null) {
            return VideoTiers.byShortSide(asset.getWidth(), asset.getHeight());
        }
        return null;
    }

    @Transactional
    public void deleteMine(Long id) {
        Asset asset = requireOwned(id);
        boolean sharedWithCreation = creationMapper.selectCount(new LambdaQueryWrapper<Creation>()
                .eq(Creation::getUserId, asset.getUserId())
                .eq(Creation::getStorageKey, asset.getStorageKey())) > 0;
        boolean sharedWithDramaReference = dramaAssetMapper.selectCount(new LambdaQueryWrapper<DramaAsset>()
                .eq(DramaAsset::getUserId, asset.getUserId())
                .and(reference -> reference
                        .eq(DramaAsset::getVoiceReferenceKey, asset.getStorageKey())
                        .or()
                        .eq(DramaAsset::getImageAssetId, asset.getId())
                        .or()
                        .eq(DramaAsset::getImageStorageKey, asset.getStorageKey()))) > 0;
        if (!sharedWithCreation && !sharedWithDramaReference) {
            objectStorage.delete(asset.getStorageKey());
            if (asset.getPosterKey() != null && !asset.getPosterKey().equals(asset.getStorageKey())) {
                try {
                    objectStorage.delete(asset.getPosterKey());
                } catch (RuntimeException ignored) {
                    // best effort
                }
            }
        }
        removeById(id);
    }

    @Transactional
    public AssetResp importFromStorage(Long userId, String name, String category, String storageKey, String posterKey) {
        Asset existing = getOne(new LambdaQueryWrapper<Asset>()
                .eq(Asset::getUserId, userId)
                .eq(Asset::getStorageKey, storageKey)
                .last("LIMIT 1"), false);
        if (existing != null) {
            return toResp(existing);
        }
        Asset asset = new Asset();
        asset.setUserId(userId);
        asset.setName(name);
        asset.setKind(category);
        asset.setMimeType(MediaUrls.contentType(storageKey, null));
        asset.setStorageKey(storageKey);
        asset.setPosterKey(AssetKind.AUDIO.equalsIgnoreCase(category) ? null
                : posterKey == null ? storageKey : posterKey);
        save(asset);
        return toResp(asset);
    }

    public AssetResp toResp(Asset asset) {
        AssetResp resp = new AssetResp();
        resp.setId(asset.getId());
        resp.setName(asset.getName());
        resp.setTitle(asset.getName());
        resp.setMimeType(asset.getMimeType());
        resp.setKind(asset.getKind());
        resp.setType(asset.getKind());
        resp.setStorageKey(asset.getStorageKey());
        resp.setPosterKey(asset.getPosterKey());
        resp.setUrl(MediaUrls.of(asset.getStorageKey()));
        resp.setSrc(MediaUrls.of(asset.getStorageKey()));
        resp.setPosterUrl(MediaUrls.of(asset.getPosterKey() == null ? asset.getStorageKey() : asset.getPosterKey()));
        resp.setPoster(resp.getPosterUrl());
        resp.setSizeBytes(asset.getSizeBytes());
        resp.setWidth(asset.getWidth());
        resp.setHeight(asset.getHeight());
        resp.setDurationMs(asset.getDurationMs());
        resp.setCreatedAt(asset.getCreatedAt());
        return resp;
    }

    private static void applyOrder(LambdaQueryWrapper<Asset> wrapper, String orderBy) {
        if ("time_asc".equalsIgnoreCase(orderBy)) {
            wrapper.orderByAsc(Asset::getCreatedAt);
            return;
        }
        if ("name_asc".equalsIgnoreCase(orderBy)) {
            wrapper.orderByAsc(Asset::getName);
            return;
        }
        if ("name_desc".equalsIgnoreCase(orderBy)) {
            wrapper.orderByDesc(Asset::getName);
            return;
        }
        if ("size_asc".equalsIgnoreCase(orderBy)) {
            wrapper.orderByAsc(Asset::getSizeBytes);
            return;
        }
        if ("size_desc".equalsIgnoreCase(orderBy)) {
            wrapper.orderByDesc(Asset::getSizeBytes);
            return;
        }
        if ("duration_desc".equalsIgnoreCase(orderBy)) {
            wrapper.orderByDesc(Asset::getDurationMs);
            return;
        }
        wrapper.orderByDesc(Asset::getCreatedAt);
    }

    private static void applyMetadata(Asset asset, MultipartFile file, String kind) {
        try {
            byte[] bytes = file.getBytes();
            AssetMetadataProbe.Metadata metadata;
            if (AssetKind.IMAGE.equals(kind)) {
                metadata = AssetMetadataProbe.probeImage(bytes);
            } else {
                String suffix = extension(file.getOriginalFilename(), file.getContentType());
                Path temp = Files.createTempFile("aigc-asset-", suffix);
                try {
                    Files.write(temp, bytes);
                    metadata = AssetMetadataProbe.probeMedia(temp);
                } finally {
                    Files.deleteIfExists(temp);
                }
            }
            asset.setWidth(metadata.width());
            asset.setHeight(metadata.height());
            asset.setDurationMs(metadata.durationMs());
        } catch (Exception ignored) {
            // 元数据仅用于展示；外部探测或临时文件异常不得影响上传。
        }
    }

    static String displayName(String filename) {
        if (!StringUtils.hasText(filename)) {
            return "未命名素材";
        }
        int slash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        String name = slash >= 0 ? filename.substring(slash + 1) : filename;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    static String assetName(String customName, String filename) {
        String name = StringUtils.hasText(customName) ? customName.trim() : displayName(filename);
        if (name.length() > 128) {
            throw new BizException(ErrorCode.BAD_REQUEST, "素材名称不能超过128个字符");
        }
        return name;
    }

    static String extension(String filename, String mime) {
        if (StringUtils.hasText(filename) && filename.contains(".")) {
            String ext = filename.substring(filename.lastIndexOf('.')).toLowerCase(Locale.ROOT);
            if (ext.length() <= 8) {
                return ext;
            }
        }
        if (mime.startsWith("video/")) {
            return ".mp4";
        }
        if (mime.startsWith("audio/")) {
            if ("audio/wav".equals(mime) || "audio/x-wav".equals(mime)) return ".wav";
            if ("audio/mp4".equals(mime) || "audio/x-m4a".equals(mime)) return ".m4a";
            return ".mp3";
        }
        if ("image/jpeg".equals(mime)) {
            return ".jpg";
        }
        if ("image/webp".equals(mime)) {
            return ".webp";
        }
        return ".png";
    }

    static String sanitizeFolder(String folder) {
        if (!StringUtils.hasText(folder)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "存储目录无效");
        }
        String value = folder.trim().replace('\\', '/');
        if (value.startsWith("/") || value.contains("..") || value.contains("//")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "存储目录无效");
        }
        return value;
    }
}
