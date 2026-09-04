package cn.aigc.dit.storage;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 未配置 S3 时的本地回退实现，便于单测与无 MinIO 的本机开发。
 */
@Slf4j
public class LocalObjectStorage implements ObjectStorage {

    private final Path root;
    private final ConcurrentHashMap<String, String> contentTypes = new ConcurrentHashMap<>();

    public LocalObjectStorage() {
        this(Path.of(System.getProperty("java.io.tmpdir"), "dit-local-storage"));
    }

    public LocalObjectStorage(Path root) {
        this.root = root;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建本地对象存储目录: " + root, e);
        }
    }

    @Override
    public void put(String storageKey, InputStream content, long contentLength, String contentType) {
        Path path = resolve(storageKey);
        try {
            Files.createDirectories(path.getParent());
            Files.copy(content, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            if (contentType != null && !contentType.isBlank()) {
                contentTypes.put(storageKey, contentType);
            }
        } catch (IOException e) {
            log.error("local put failed, key={}", storageKey, e);
            throw new BizException(ErrorCode.INTERNAL, "对象存储写入失败");
        }
    }

    @Override
    public InputStream get(String storageKey) {
        Path path = resolve(storageKey);
        try {
            return new ByteArrayInputStream(Files.readAllBytes(path));
        } catch (IOException e) {
            throw new BizException(ErrorCode.NOT_FOUND, "对象不存在");
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(resolve(storageKey));
            contentTypes.remove(storageKey);
        } catch (IOException e) {
            log.error("local delete failed, key={}", storageKey, e);
            throw new BizException(ErrorCode.INTERNAL, "对象存储删除失败");
        }
    }

    @Override
    public String presignGet(String storageKey, Duration expiry) {
        return storageKey;
    }

    @Override
    public String presignPut(String storageKey, Duration expiry, String contentType) {
        return storageKey;
    }

    private Path resolve(String storageKey) {
        if (storageKey == null || storageKey.isBlank() || storageKey.contains("..")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "非法存储路径");
        }
        return root.resolve(storageKey).normalize();
    }
}
