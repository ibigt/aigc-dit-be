package cn.aigc.dit.generate.sglang;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.io.IOException;

final class JdkSglangHttp implements SglangHttp {
    private final HttpClient client;

    JdkSglangHttp() {
        this(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).build());
    }

    JdkSglangHttp(HttpClient client) { this.client = client; }

    @Override
    public byte[] request(String method, URI uri, String apiKey, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                // Avoid h2c upgrade requests rejected by Uvicorn's HTTP/1.1 parser.
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofSeconds(uri.getPath().endsWith("/content") ? 300 : 60));
        if (apiKey != null && !apiKey.isBlank()) builder.header("Authorization", "Bearer " + apiKey.trim());
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        try {
            HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String detail = new String(response.body(), StandardCharsets.UTF_8);
                throw new BizException(ErrorCode.INTERNAL, "SGLang HTTP " + response.statusCode()
                        + ": " + detail.substring(0, Math.min(detail.length(), 300)));
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.INTERNAL, "SGLang 请求被中断");
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL, "SGLang 网络请求失败或超时");
        }
    }
}
