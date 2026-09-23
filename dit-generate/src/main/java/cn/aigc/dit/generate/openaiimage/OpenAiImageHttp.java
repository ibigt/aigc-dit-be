package cn.aigc.dit.generate.openaiimage;

/**
 * OpenAI Images HTTP 出站，便于单测替换。
 */
public interface OpenAiImageHttp {

    String postJson(java.net.URI uri, String bearerToken, String jsonBody);

    String postMultipart(java.net.URI uri, String bearerToken, byte[] multipartBody, String boundary);
}
