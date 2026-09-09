package cn.aigc.dit.generate.comfyui;

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
 * JDK {@link HttpClient} 实现 ComfyUI JSON / 上传 / 成片下载。
 */
@Slf4j
public class JdkComfyUiHttp implements ComfyUiHttp {

    private static final String USER_AGENT = "aigc-dit/1.0 (ComfyUI)";

    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final Duration downloadTimeout;

    public JdkComfyUiHttp(GenerateProperties.Comfyui properties) {
        this.requestTimeout = Duration.ofSeconds(Math.max(5, properties.getRequestTimeoutSeconds()));
        this.downloadTimeout = Duration.ofSeconds(Math.max(30, properties.getDownloadTimeoutSeconds()));
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(Math.max(3, properties.getConnectTimeoutSeconds())))
                .build();
    }

    JdkComfyUiHttp(HttpClient httpClient, Duration requestTimeout) {
        this.httpClient = httpClient;
        this.requestTimeout = requestTimeout;
        this.downloadTimeout = requestTimeout;
    }

    @Override
    public String postJson(URI uri, String bearerToken, String jsonBody) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "{}" : jsonBody, StandardCharsets.UTF_8));
        applyAuth(builder, bearerToken);
        return sendString(builder.build());
    }

    @Override
    public String getJson(URI uri, String bearerToken) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("User-Agent", USER_AGENT)
                .GET();
        applyAuth(builder, bearerToken);
        return sendString(builder.build());
    }

    @Override
    public byte[] getBytes(URI uri, String bearerToken) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(downloadTimeout)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
                .GET();
        applyAuth(builder, bearerToken);
        try {
            HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BizException(ErrorCode.INTERNAL, "下载 ComfyUI 成片失败 HTTP " + response.statusCode());
            }
            byte[] body = response.body();
            if (body == null || body.length == 0) {
                throw new BizException(ErrorCode.INTERNAL, "ComfyUI 成片为空");
            }
            return body;
        } catch (BizException e) {
            throw e;
        } catch (HttpTimeoutException e) {
            throw new BizException(ErrorCode.INTERNAL, "下载 ComfyUI 成片超时");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.INTERNAL, "下载 ComfyUI 成片被中断");
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL, "下载 ComfyUI 成片失败");
        }
    }

    @Override
    public String uploadImage(URI uri, String bearerToken, String filename, String contentType, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "参考素材文件为空");
        }
        String safeName = filename == null || filename.isBlank() ? "ref.bin" : filename.replace("\"", "_");
        String mime = contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
        String boundary = "----ditComfy" + UUID.randomUUID().toString().replace("-", "");
        byte[] payload = multipart(boundary, safeName, mime, bytes);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
        applyAuth(builder, bearerToken);
        return sendString(builder.build());
    }

    private String sendString(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            String body = response.body() == null ? "" : response.body();
            if (status < 200 || status >= 300) {
                throw new BizException(ErrorCode.INTERNAL, "ComfyUI HTTP " + status
                        + (body.isBlank() ? "" : ": " + truncate(body, 240)));
            }
            return body;
        } catch (BizException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.INTERNAL, "ComfyUI 请求被中断");
        } catch (HttpTimeoutException e) {
            throw new BizException(ErrorCode.INTERNAL, "ComfyUI 请求超时");
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL, "ComfyUI 请求失败");
        }
    }

    private static void applyAuth(HttpRequest.Builder builder, String bearerToken) {
        if (bearerToken != null && !bearerToken.isBlank()) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
    }

    private static byte[] multipart(String boundary, String filename, String contentType, byte[] bytes) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            String fileHeader = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"image\"; filename=\"" + filename + "\"\r\n"
                    + "Content-Type: " + contentType + "\r\n\r\n";
            out.write(fileHeader.getBytes(StandardCharsets.UTF_8));
            out.write(bytes);
            String overwrite = "\r\n--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"overwrite\"\r\n\r\ntrue";
            out.write(overwrite.getBytes(StandardCharsets.UTF_8));
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL, "组装 ComfyUI 上传请求失败");
        }
    }

    private static String truncate(String text, int max) {
        return text.length() > max ? text.substring(0, max) : text;
    }
}
