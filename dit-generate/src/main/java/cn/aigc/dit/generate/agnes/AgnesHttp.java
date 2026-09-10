package cn.aigc.dit.generate.agnes;

import java.net.URI;

/**
 * Agnes HTTP 出站，便于单测替换。
 */
public interface AgnesHttp {

    String postJson(URI uri, String bearerToken, String jsonBody);

    String getJson(URI uri, String bearerToken);

    byte[] getBytes(URI uri);
}
