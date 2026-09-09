package cn.aigc.dit.generate.comfyui;

import java.net.URI;

/**
 * ComfyUI HTTP 出站，便于单测替换。
 */
public interface ComfyUiHttp {

    String postJson(URI uri, String bearerToken, String jsonBody);

    String getJson(URI uri, String bearerToken);

    byte[] getBytes(URI uri, String bearerToken);

    String uploadImage(URI uri, String bearerToken, String filename, String contentType, byte[] bytes);
}
