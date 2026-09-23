package cn.aigc.dit.generate.seedream;

import java.net.URI;

/**
 * Seedream 出站 HTTP：JSON 提交与成片 URL 下载。
 */
public interface SeedreamHttp {

    String postJson(URI uri, String bearerToken, String jsonBody);

    byte[] getBytes(URI uri);
}
