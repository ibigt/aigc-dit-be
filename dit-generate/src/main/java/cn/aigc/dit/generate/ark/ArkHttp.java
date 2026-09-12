package cn.aigc.dit.generate.ark;

import java.net.URI;

/**
 * 火山方舟 HTTP 出站，便于单测替换。
 */
public interface ArkHttp {

    String postJson(URI uri, String bearerToken, String jsonBody);

    String getJson(URI uri, String bearerToken);

    byte[] getBytes(URI uri);
}
