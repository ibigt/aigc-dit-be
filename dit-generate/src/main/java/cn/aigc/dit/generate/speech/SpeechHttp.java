package cn.aigc.dit.generate.speech;

import java.net.URI;

interface SpeechHttp {

    byte[] post(URI uri, String apiKey, String json);
}
