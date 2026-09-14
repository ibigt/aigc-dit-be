package cn.aigc.dit.generate.minimax;

import java.net.URI;

public interface MiniMaxHttp {

    String postJson(URI uri, String bearerToken, String jsonBody);

    String getJson(URI uri, String bearerToken);

    byte[] getBytes(URI uri);
}
