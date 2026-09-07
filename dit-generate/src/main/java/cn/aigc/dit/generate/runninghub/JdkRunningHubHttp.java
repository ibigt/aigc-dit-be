package cn.aigc.dit.generate.runninghub;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.generate.GenerateProperties;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * JDK {@link HttpClient} 实现：JSON 提交/查询，以及成片 URL 下载。
 */
@Slf4j
public class JdkRunningHubHttp implements RunningHubHttp {

    private static final String USER_AGENT = "Mozilla/5.0 (compatible; aigc-dit/1.0; +https://www.runninghub.ai)";

    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final Duration downloadTimeout;
    private final int downloadRetries;

    public JdkRunningHubHttp(GenerateProperties.Runninghub properties) {
        this.requestTimeout = Duration.ofSeconds(Math.max(5, properties.getRequestTimeoutSeconds()));
        this.downloadTimeout = Duration.ofSeconds(Math.max(30, properties.getDownloadTimeoutSeconds()));
        this.downloadRetries = Math.max(1, properties.getDownloadRetries());
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(Math.max(3, properties.getConnectTimeoutSeconds())))
                .build();
    }

    JdkRunningHubHttp(HttpClient httpClient, Duration requestTimeout) {
        this(httpClient, requestTimeout, requestTimeout, 1);
    }

    JdkRunningHubHttp(HttpClient httpClient, Duration requestTimeout, Duration downloadTimeout, int downloadRetries) {
        this.httpClient = httpClient;
        this.requestTimeout = requestTimeout;
        this.downloadTimeout = downloadTimeout;
        this.downloadRetries = Math.max(1, downloadRetries);
    }

    @Override
    public String postJson(URI uri, String bearerToken, String jsonBody) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        if (bearerToken != null && !bearerToken.isBlank()) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        return send(builder.build());
    }

    @Override
    public String uploadBinary(URI uri, String bearerToken, String filename, String contentType, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "参考素材文件为空");
        }
        String safeName = filename == null || filename.isBlank() ? "ref.bin" : filename.replace("\"", "_");
        String mime = contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
        String boundary = "----ditRh" + UUID.randomUUID().toString().replace("-", "");
        byte[] payload = multipartFile(boundary, safeName, mime, bytes);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
        if (bearerToken != null && !bearerToken.isBlank()) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        return send(builder.build());
    }

    @Override
    public byte[] getBytes(URI uri) {
        BizException last = null;
        for (int attempt = 1; attempt <= downloadRetries; attempt++) {
            try {
                return downloadOnce(uri);
            } catch (BizException e) {
                last = e;
                log.warn("RunningHub download attempt {}/{} failed host={} : {}",
                        attempt, downloadRetries, uri.getHost(), e.getMessage());
                if (attempt < downloadRetries && retryable(e)) {
                    sleep(1000L * attempt);
                    continue;
                }
                throw e;
            }
        }
        throw last == null ? new BizException(ErrorCode.INTERNAL, "下载 RunningHub 成片失败") : last;
    }

    private byte[] downloadOnce(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(downloadTimeout)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
                .header("Referer", "https://www.runninghub.ai/")
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BizException(ErrorCode.INTERNAL, "下载 RunningHub 成片失败 HTTP " + response.statusCode());
            }
            byte[] body = response.body();
            if (body == null || body.length == 0) {
                throw new BizException(ErrorCode.INTERNAL, "RunningHub 成片为空");
            }
            return body;
        } catch (BizException e) {
            throw e;
        } catch (HttpTimeoutException e) {
            throw new BizException(ErrorCode.INTERNAL, "下载 RunningHub 成片超时");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.INTERNAL, "下载 RunningHub 成片被中断");
        } catch (IOException e) {
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new BizException(ErrorCode.INTERNAL, "下载 RunningHub 成片失败: " + truncate(detail, 180));
        }
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            String body = response.body() == null ? "" : response.body();
            if (status < 200 || status >= 300) {
                throw new BizException(ErrorCode.INTERNAL, "RunningHub HTTP " + status
                        + (body.isBlank() ? "" : ": " + truncate(body, 240)));
            }
            return body;
        } catch (BizException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.INTERNAL, "RunningHub 请求被中断");
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL, "RunningHub 请求失败");
        }
    }

    private static boolean retryable(BizException error) {
        String message = error.getMessage() == null ? "" : error.getMessage();
        return message.contains("超时") || message.contains("失败") || message.contains("HTTP 5");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static byte[] multipartFile(String boundary, String filename, String contentType, byte[] bytes) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            String header = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                    + "Content-Type: " + contentType + "\r\n\r\n";
            out.write(header.getBytes(StandardCharsets.UTF_8));
            out.write(bytes);
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL, "组装 RunningHub 上传请求失败");
        }
    }

    private static String truncate(String text, int max) {
        return text.length() > max ? text.substring(0, max) : text;
    }
}
