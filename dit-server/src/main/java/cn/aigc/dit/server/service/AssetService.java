package cn.aigc.dit.server.service;

import cn.aigc.dit.common.constant.AssetKind;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.generate.poster.PosterExtractor;
import cn.aigc.dit.server.entity.Asset;
import cn.aigc.dit.server.entity.Creation;
import cn.aigc.dit.server.mapper.AssetMapper;
import cn.aigc.dit.server.mapper.CreationMapper;
import cn.aigc.dit.server.media.MediaUrls;
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
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AssetService extends ServiceImpl<AssetMapper, Asset> {

    private final ObjectStorage objectStorage;
    private final PosterExtractor posterExtractor;
    private final CreationMapper creationMapper;

    public List<AssetResp> listMine(String kind, String query) {
        Long userId = UserContext.requireUserId();
        LambdaQueryWrapper<Asset> wrapper = new LambdaQueryWrapper<Asset>()
                .eq(Asset::getUserId, userId)
                .eq(StringUtils.hasText(kind) && !"all".equalsIgnoreCase(kind), Asset::getKind, kind)
                .like(StringUtils.hasText(query), Asset::getName, query)
                .orderByDesc(Asset::getCreatedAt);
        return list(wrapper).stream().map(this::toResp).toList();
    }

    @Transactional
    public AssetResp upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择文件");
        }
        String mime = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!mime.startsWith("image/") && !mime.startsWith("video/")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "仅支持图片或视频");
        }
        Long userId = UserContext.requireUserId();
        String ext = extension(file.getOriginalFilename(), mime);
        String key = "users/" + userId + "/assets/" + UUID.randomUUID() + ext;
        try {
            objectStorage.put(key, file.getBytes(), mime);
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL, "读取上传文件失败");
        }
        String kind = AssetKind.fromMime(mime);
        String posterKey = AssetKind.VIDEO.equals(kind) ? posterExtractor.extract(key) : key;
        Asset asset = new Asset();
        asset.setUserId(userId);
        asset.setName(displayName(file.getOriginalFilename()));
        asset.setMimeType(mime);
        asset.setKind(kind);
        asset.setStorageKey(key);
        asset.setPosterKey(posterKey);
        asset.setSizeBytes(file.getSize());
        save(asset);
        return toResp(asset);
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

    @Transactional
    public void deleteMine(Long id) {
        Asset asset = requireOwned(id);
        boolean sharedWithCreation = creationMapper.selectCount(new LambdaQueryWrapper<Creation>()
                .eq(Creation::getUserId, asset.getUserId())
                .eq(Creation::getStorageKey, asset.getStorageKey())) > 0;
        if (!sharedWithCreation) {
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
        Asset asset = new Asset();
        asset.setUserId(userId);
        asset.setName(name);
        asset.setKind(category);
        asset.setMimeType(MediaUrls.contentType(storageKey, null));
        asset.setStorageKey(storageKey);
        asset.setPosterKey(posterKey == null ? storageKey : posterKey);
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
        resp.setCreatedAt(asset.getCreatedAt());
        return resp;
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
        if ("image/jpeg".equals(mime)) {
            return ".jpg";
        }
        if ("image/webp".equals(mime)) {
            return ".webp";
        }
        return ".png";
    }
}
