package cn.aigc.dit.generate.openaiimage;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.generate.GenerateProperties;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Slf4j
public class JdkOpenAiImageHttp implements OpenAiImageHttp {

    private static final String USER_AGENT = "Mozilla/5.0 (compatible; aigc-dit/1.0; +https://platform.openai.com)";

    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public JdkOpenAiImageHttp(GenerateProperties.OpenAiImage properties) {
        this.requestTimeout = Duration.ofSeconds(Math.max(60, properties.getRequestTimeoutSeconds()));
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(Math.max(3, properties.getConnectTimeoutSeconds())))
                .build();
    }

    JdkOpenAiImageHttp(HttpClient httpClient, Duration requestTimeout) {
        this.httpClient = httpClient;
        this.requestTimeout = requestTimeout;
    }

    @Override
    public String postJson(URI uri, String bearerToken, String jsonBody) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        applyBearer(builder, bearerToken);
        return send(builder.build());
    }

    @Override
    public String postMultipart(URI uri, String bearerToken, byte[] multipartBody, String boundary) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody));
        applyBearer(builder, bearerToken);
        return send(builder.build());
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            String body = response.body() == null ? "" : response.body();
            if (status >= 200 && status < 300) {
                return body;
            }
            throw new BizException(ErrorCode.INTERNAL, openaiError(status, body));
        } catch (HttpTimeoutException e) {
            throw new BizException(ErrorCode.INTERNAL, "OpenAI Images 请求超时");
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL, "OpenAI Images 网络异常: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.INTERNAL, "OpenAI Images 请求被中断");
        }
    }

    private static void applyBearer(HttpRequest.Builder builder, String bearerToken) {
        if (bearerToken != null && !bearerToken.isBlank()) {
            builder.header("Authorization", "Bearer " + bearerToken.trim());
        }
    }

    private static String openaiError(int status, String body) {
        String detail = body == null ? "" : body.trim();
        if (detail.length() > 240) {
            detail = detail.substring(0, 240);
        }
        return "OpenAI Images 调用失败 HTTP " + status + (detail.isBlank() ? "" : ": " + detail);
    }
}
