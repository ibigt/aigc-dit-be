package cn.aigc.dit.generate.sglang;

import java.net.URI;

interface SglangHttp {
    byte[] request(String method, URI uri, String apiKey, String body);
}
