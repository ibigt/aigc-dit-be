package cn.aigc.dit.generate.sglang;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.generate.GenerateProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.io.IOException;

final class JdkSglangHttp implements SglangHttp {
    private final HttpClient client;
    private final GenerateProperties.Sglang settings;

    JdkSglangHttp() {
        this(new GenerateProperties.Sglang());
    }

    JdkSglangHttp(GenerateProperties.Sglang settings) {
        this(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).build(), settings);
    }

    JdkSglangHttp(HttpClient client) { this(client, new GenerateProperties.Sglang()); }

    JdkSglangHttp(HttpClient client, GenerateProperties.Sglang settings) {
        this.client = client;
        this.settings = settings;
    }

    @Override
    public byte[] request(String method, URI uri, String apiKey, String body) {
        boolean submit = "POST".equals(method);
        boolean download = uri.getPath().endsWith("/content");
        String phase = submit ? "提交" : download ? "下载" : "查询";
        int timeout = submit ? settings.getRequestTimeoutSeconds()
                : download ? settings.getDownloadTimeoutSeconds() : settings.getPollTimeoutSeconds();
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                // Avoid h2c upgrade requests rejected by Uvicorn's HTTP/1.1 parser.
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofSeconds(Math.max(1, timeout)));
        if (apiKey != null && !apiKey.isBlank()) builder.header("Authorization", "Bearer " + apiKey.trim());
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        try {
            HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String detail = new String(response.body(), StandardCharsets.UTF_8);
                throw new BizException(ErrorCode.INTERNAL, "SGLang " + phase + " HTTP " + response.statusCode()
                        + ": " + detail.substring(0, Math.min(detail.length(), 300)));
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.INTERNAL, "SGLang 请求被中断");
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL, "SGLang " + phase + "网络请求失败或超时"
                    + (submit ? "；未收到任务 ID，远端可能已开始生成，请核查服务端任务后再重试" : ""));
        }
    }
}
