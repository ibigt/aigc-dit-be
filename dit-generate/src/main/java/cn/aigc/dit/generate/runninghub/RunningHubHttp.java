package cn.aigc.dit.generate.runninghub;

import java.net.URI;

/**
 * RunningHub HTTP 出站，便于单测替换。
 */
public interface RunningHubHttp {

    String postJson(URI uri, String bearerToken, String jsonBody);

    /**
     * 上传参考素材，返回 RunningHub JSON（含 data.download_url）。
     */
    String uploadBinary(URI uri, String bearerToken, String filename, String contentType, byte[] bytes);

    byte[] getBytes(URI uri);
}
