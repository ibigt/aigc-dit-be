package cn.aigc.dit.generate.speech;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.generate.GenerateProperties;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

final class JdkSpeechHttp implements SpeechHttp {

    private final HttpClient client;
    private final Duration requestTimeout;

    JdkSpeechHttp(GenerateProperties.Speech properties) {
        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, properties.getConnectTimeoutSeconds())))
                .build();
        requestTimeout = Duration.ofSeconds(Math.max(1, properties.getRequestTimeoutSeconds()));
    }

    @Override
    public byte[] post(URI uri, String apiKey, String json) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "audio/*, application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey.trim());
            }
            HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String message = new String(response.body(), StandardCharsets.UTF_8);
                if (message.length() > 1000) {
                    message = message.substring(0, 1000);
                }
                throw new BizException(ErrorCode.INTERNAL,
                        "语音生成 HTTP " + response.statusCode() + ": " + message);
            }
            return response.body();
        } catch (BizException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.INTERNAL, "语音生成请求被中断");
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL, "语音生成请求失败: " + e.getMessage());
        }
    }

    @Override
    public String getString(URI uri, String apiKey) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(requestTimeout)
                    .header("Accept", "application/json")
                    .GET();
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey.trim());
            }
            HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            String body = new String(response.body(), StandardCharsets.UTF_8);
            if (status < 200 || status >= 300) {
                if (body.length() > 240) {
                    body = body.substring(0, 240);
                }
                throw new BizException(ErrorCode.INTERNAL, "语音模型 HTTP " + status + ": " + body);
            }
            return body;
        } catch (BizException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.INTERNAL, "语音模型请求被中断");
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL, "语音模型请求失败: " + e.getMessage());
        }
    }
}
