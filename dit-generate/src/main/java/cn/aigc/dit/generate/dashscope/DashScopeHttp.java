package cn.aigc.dit.generate.dashscope;

import java.net.URI;

public interface DashScopeHttp {

    String postJson(URI uri, String bearerToken, String jsonBody, boolean asyncHeader);

    String getJson(URI uri, String bearerToken);

    byte[] getBytes(URI uri);
}
