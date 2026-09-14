package cn.aigc.dit.storage;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;

/**
 * S3 兼容对象存储。库表只存相对 {@code storage_key}，不存绝对 URL。
 */
public interface ObjectStorage {

    void put(String storageKey, InputStream content, long contentLength, String contentType);

    default void put(String storageKey, byte[] content, String contentType) {
        byte[] data = content == null ? new byte[0] : content;
        put(storageKey, new ByteArrayInputStream(data), data.length, contentType);
    }

    InputStream get(String storageKey);

    default byte[] getBytes(String storageKey) {
        try (InputStream in = get(storageKey)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL, "读取对象失败");
        }
    }

    default long size(String storageKey) {
        return getBytes(storageKey).length;
    }

    /**
     * 闭区间字节切片，供视频预览 HTTP Range / 进度条拖动。
     */
    default InputStream getRange(String storageKey, long startInclusive, long endInclusive) {
        byte[] all = getBytes(storageKey);
        if (startInclusive < 0 || endInclusive >= all.length || startInclusive > endInclusive) {
            throw new BizException(ErrorCode.BAD_REQUEST, "非法字节范围");
        }
        int length = (int) (endInclusive - startInclusive + 1);
        return new ByteArrayInputStream(all, (int) startInclusive, length);
    }

    void delete(String storageKey);

    /**
     * 预签名下载地址，供前端通过后端下发的 URL 取对象，不直连 MinIO。
     */
    String presignGet(String storageKey, Duration expiry);

    /**
     * 预签名上传地址。
     */
    String presignPut(String storageKey, Duration expiry, String contentType);
}
