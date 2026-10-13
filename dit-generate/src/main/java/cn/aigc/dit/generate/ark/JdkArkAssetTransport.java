package cn.aigc.dit.generate.ark;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.generate.GenerateProperties;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * JDK HttpClient。Host 由客户端按 URL 自动带上，签名里的 host 必须与它一致，这里不再手写 Host。
 */
final class JdkArkAssetTransport implements ArkAssetTransport {

    private final HttpClient httpClient;
    private final Duration timeout;

    JdkArkAssetTransport(GenerateProperties.Ark properties) {
        this.timeout = Duration.ofSeconds(Math.max(30, properties.getRequestTimeoutSeconds()));
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(Math.max(3, properties.getConnectTimeoutSeconds())))
                .build();
    }

    @Override
    public ArkAssetHttpResult post(URI uri, Map<String, String> headers, byte[] body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Content-Type", headers.getOrDefault("content-type", "application/json"))
                .header("X-Date", headers.get("x-date"))
                .header("X-Content-Sha256", headers.get("x-content-sha256"))
                .header("Authorization", headers.get("authorization"))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body == null ? new byte[0] : body));
        try {
            HttpResponse<String> response = httpClient.send(
                    builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String responseBody = response.body() == null ? "" : response.body();
            return new ArkAssetHttpResult(response.statusCode(), responseBody);
        } catch (HttpTimeoutException e) {
            throw new BizException(ErrorCode.INTERNAL, "方舟素材库请求超时");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.INTERNAL, "方舟素材库请求被中断");
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL, "方舟素材库请求失败");
        }
    }
}
