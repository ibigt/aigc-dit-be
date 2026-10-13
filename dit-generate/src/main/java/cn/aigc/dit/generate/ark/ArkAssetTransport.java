package cn.aigc.dit.generate.ark;

import java.net.URI;
import java.util.Map;

interface ArkAssetTransport {

    ArkAssetHttpResult post(URI uri, Map<String, String> headers, byte[] body);
}
