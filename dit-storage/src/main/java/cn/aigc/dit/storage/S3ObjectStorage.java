package cn.aigc.dit.storage;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.InputStream;
import java.time.Duration;

@Slf4j
@RequiredArgsConstructor
public class S3ObjectStorage implements ObjectStorage {

    /** 列表封面的 head 不能干等客户端默认的 8 秒，否则几百张图会占满页面连接。 */
    private static final AwsRequestOverrideConfiguration HEAD_LIMIT = AwsRequestOverrideConfiguration.builder()
            .apiCallAttemptTimeout(Duration.ofSeconds(2))
            .apiCallTimeout(Duration.ofSeconds(2))
            .build();

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final StorageProperties properties;
    private final S3AvailabilityGuard availability = new S3AvailabilityGuard();

    @Override
    public void put(String storageKey, InputStream content, long contentLength, String contentType) {
        availability.enter();
        try {
            PutObjectRequest.Builder builder = PutObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(storageKey);
            if (contentType != null && !contentType.isBlank()) {
                builder.contentType(contentType);
            }
            s3Client.putObject(builder.build(), RequestBody.fromInputStream(content, contentLength));
            availability.reachable();
        } catch (RuntimeException e) {
            throw finishWrite("put", storageKey, e, "对象存储写入失败");
        }
    }

    @Override
    public InputStream get(String storageKey) {
        availability.enter();
        try {
            InputStream stream = s3Client.getObject(GetObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(storageKey)
                    .build());
            availability.reachable();
            return stream;
        } catch (RuntimeException e) {
            throw finishRead("get", storageKey, e);
        }
    }

    @Override
    public boolean exists(String storageKey) {
        try {
            head(storageKey);
            return true;
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            if (isNotFound(e)) {
                return false;
            }
            log.warn("S3 head failed, key={} reason={}", storageKey, e.toString());
            throw unavailable();
        }
    }

    @Override
    public long size(String storageKey) {
        try {
            Long length = head(storageKey).contentLength();
            if (length == null) {
                throw new BizException(ErrorCode.INTERNAL, "对象大小未知");
            }
            return length;
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            if (isNotFound(e)) {
                throw new BizException(ErrorCode.NOT_FOUND, "对象不存在");
            }
            log.warn("S3 head failed, key={} reason={}", storageKey, e.toString());
            throw unavailable();
        }
    }

    @Override
    public InputStream getRange(String storageKey, long startInclusive, long endInclusive) {
        availability.enter();
        try {
            InputStream stream = s3Client.getObject(GetObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(storageKey)
                    .range("bytes=" + startInclusive + "-" + endInclusive)
                    .build());
            availability.reachable();
            return stream;
        } catch (RuntimeException e) {
            throw finishRead("range get", storageKey, e);
        }
    }

    @Override
    public void delete(String storageKey) {
        availability.enter();
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(storageKey)
                    .build());
            availability.reachable();
        } catch (RuntimeException e) {
            throw finishWrite("delete", storageKey, e, "对象存储删除失败");
        }
    }

    private HeadObjectResponse head(String storageKey) {
        availability.enter();
        try {
            HeadObjectResponse response = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(storageKey)
                    .overrideConfiguration(HEAD_LIMIT)
                    .build());
            availability.reachable();
            return response;
        } catch (RuntimeException e) {
            if (isNotFound(e)) {
                availability.reachable();
                throw e;
            }
            if (isTimeout(e)) {
                noteTimeout("head", storageKey);
                throw unavailable();
            }
            availability.reachable();
            throw e;
        }
    }

    private BizException finishRead(String operation, String storageKey, RuntimeException error) {
        if (isTimeout(error)) {
            noteTimeout(operation, storageKey);
            return unavailable();
        }
        availability.reachable();
        log.error("S3 {} failed, key={}", operation, storageKey, error);
        return new BizException(ErrorCode.NOT_FOUND, "对象不存在");
    }

    private BizException finishWrite(String operation, String storageKey, RuntimeException error, String message) {
        if (error instanceof BizException biz) {
            throw biz;
        }
        if (isTimeout(error)) {
            noteTimeout(operation, storageKey);
            return unavailable();
        }
        availability.reachable();
        log.error("S3 {} failed, key={}", operation, storageKey, error);
        return new BizException(ErrorCode.INTERNAL, message);
    }

    private void noteTimeout(String operation, String storageKey) {
        if (availability.timeout()) {
            log.warn("S3 circuit open for 15s after repeated timeouts");
        } else {
            log.warn("S3 {} timed out, key={}", operation, storageKey);
        }
    }

    private static boolean isNotFound(Throwable error) {
        if (error instanceof NoSuchKeyException) {
            return true;
        }
        return error instanceof S3Exception s3 && s3.statusCode() == 404;
    }

    private static boolean isTimeout(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof ApiCallAttemptTimeoutException || current instanceof ApiCallTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static BizException unavailable() {
        return new BizException(ErrorCode.UNAVAILABLE, "对象存储暂不可用");
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
