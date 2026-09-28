package cn.aigc.dit.generate.speech;

import java.net.URI;

interface SpeechHttp {

    byte[] post(URI uri, String apiKey, String json);

    /** 管理端连通性测试使用；服务端错误以 BizException 形式抛出。 */
    String getString(URI uri, String apiKey);
}
