package cn.aigc.dit.storage;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.InputStream;
import java.time.Duration;

@Slf4j
@RequiredArgsConstructor
public class S3ObjectStorage implements ObjectStorage {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final StorageProperties properties;

    @Override
    public void put(String storageKey, InputStream content, long contentLength, String contentType) {
        try {
            PutObjectRequest.Builder builder = PutObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(storageKey);
            if (contentType != null && !contentType.isBlank()) {
                builder.contentType(contentType);
            }
            s3Client.putObject(builder.build(), RequestBody.fromInputStream(content, contentLength));
        } catch (Exception e) {
            log.error("S3 put failed, key={}", storageKey, e);
            throw new BizException(ErrorCode.INTERNAL, "对象存储写入失败");
        }
    }

    @Override
    public InputStream get(String storageKey) {
        try {
            return s3Client.getObject(GetObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(storageKey)
                    .build());
        } catch (Exception e) {
            log.error("S3 get failed, key={}", storageKey, e);
            throw new BizException(ErrorCode.NOT_FOUND, "对象不存在");
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(storageKey)
                    .build());
        } catch (Exception e) {
            log.error("S3 delete failed, key={}", storageKey, e);
            throw new BizException(ErrorCode.INTERNAL, "对象存储删除失败");
        }
    }

    @Override
    public String presignGet(String storageKey, Duration expiry) {
        GetObjectPresignRequest request = GetObjectPresignRequest.builder()
                .signatureDuration(expiry)
                .getObjectRequest(GetObjectRequest.builder()
                        .bucket(properties.getBucket())
                        .key(storageKey)
                        .build())
                .build();
        return s3Presigner.presignGetObject(request).url().toString();
    }

    @Override
    public String presignPut(String storageKey, Duration expiry, String contentType) {
        PutObjectRequest.Builder put = PutObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(storageKey);
        if (contentType != null && !contentType.isBlank()) {
            put.contentType(contentType);
        }
        PutObjectPresignRequest request = PutObjectPresignRequest.builder()
                .signatureDuration(expiry)
                .putObjectRequest(put.build())
                .build();
        return s3Presigner.presignPutObject(request).url().toString();
    }
}
