package cn.aigc.dit.generate.agnes;

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

/**
 * JDK {@link HttpClient} 实现：Bearer JSON 提交/查询，以及成片 URL 下载。
 */
@Slf4j
public class JdkAgnesHttp implements AgnesHttp {

    private static final String USER_AGENT = "Mozilla/5.0 (compatible; aigc-dit/1.0; +https://www.agnes-ai.cn)";

    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final Duration pollTimeout;
    private final Duration downloadTimeout;
    private final int downloadRetries;

    public JdkAgnesHttp(GenerateProperties.Agnes properties) {
        this.requestTimeout = Duration.ofSeconds(Math.max(30, properties.getRequestTimeoutSeconds()));
        this.pollTimeout = Duration.ofSeconds(Math.max(5, properties.getPollTimeoutSeconds()));
        this.downloadTimeout = Duration.ofSeconds(Math.max(30, properties.getDownloadTimeoutSeconds()));
        this.downloadRetries = Math.max(1, properties.getDownloadRetries());
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(Math.max(3, properties.getConnectTimeoutSeconds())))
                .build();
    }

    JdkAgnesHttp(HttpClient httpClient, Duration requestTimeout) {
        this(httpClient, requestTimeout, requestTimeout, requestTimeout, 1);
    }

    JdkAgnesHttp(HttpClient httpClient, Duration requestTimeout, Duration pollTimeout,
                 Duration downloadTimeout, int downloadRetries) {
        this.httpClient = httpClient;
        this.requestTimeout = requestTimeout;
        this.pollTimeout = pollTimeout;
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
        applyBearer(builder, bearerToken);
        return send(builder.build(), true);
    }

    @Override
    public String getJson(URI uri, String bearerToken) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(pollTimeout)
                .header("User-Agent", USER_AGENT)
                .GET();
        applyBearer(builder, bearerToken);
        return send(builder.build(), false);
    }

    @Override
    public byte[] getBytes(URI uri) {
        BizException last = null;
        for (int attempt = 1; attempt <= downloadRetries; attempt++) {
            try {
                return downloadOnce(uri);
            } catch (BizException e) {
                last = e;
                log.warn("Agnes download attempt {}/{} failed host={} : {}",
                        attempt, downloadRetries, uri.getHost(), e.getMessage());
                if (attempt < downloadRetries && retryable(e)) {
                    sleep(1000L * attempt);
                    continue;
                }
                throw e;
            }
        }
        throw last == null ? new BizException(ErrorCode.INTERNAL, "下载 Agnes 成片失败") : last;
    }

    private byte[] downloadOnce(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(downloadTimeout)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BizException(ErrorCode.INTERNAL, "下载 Agnes 成片失败 HTTP " + response.statusCode());
            }
            byte[] body = response.body();
            if (body == null || body.length == 0) {
                throw new BizException(ErrorCode.INTERNAL, "Agnes 成片为空");
            }
            return body;
        } catch (BizException e) {
            throw e;
        } catch (HttpTimeoutException e) {
            throw new BizException(ErrorCode.INTERNAL, "下载 Agnes 成片超时");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.INTERNAL, "下载 Agnes 成片被中断");
        } catch (IOException e) {
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new BizException(ErrorCode.INTERNAL, "下载 Agnes 成片失败: " + truncate(detail, 180));
        }
    }

    private String send(HttpRequest request, boolean submit) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            String body = response.body() == null ? "" : response.body();
            if (status >= 400 && status < 500 && status != 429) {
                throw new BizException(ErrorCode.BAD_REQUEST, agnesError(status, body, submit));
            }
            if (status < 200 || status >= 300) {
                throw new BizException(ErrorCode.INTERNAL, agnesError(status, body, submit));
            }
            return body;
        } catch (BizException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.INTERNAL, "Agnes 请求被中断");
        } catch (HttpTimeoutException e) {
            throw new BizException(ErrorCode.INTERNAL, submit ? "Agnes 请求超时" : "Agnes 查询超时");
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL, "Agnes 请求失败");
        }
    }

    private static void applyBearer(HttpRequest.Builder builder, String bearerToken) {
        if (bearerToken != null && !bearerToken.isBlank()) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
    }

    static String agnesError(int status, String body, boolean submit) {
        String detail = AgnesErrors.message(body);
        String prefix = submit ? "Agnes HTTP " : "Agnes 查询 HTTP ";
        if (detail == null || detail.isBlank()) {
            return prefix + status + (body == null || body.isBlank() ? "" : ": " + truncate(body, 240));
        }
        return prefix + status + ": " + detail;
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

    private static String truncate(String text, int max) {
        return text.length() > max ? text.substring(0, max) : text;
    }
}
