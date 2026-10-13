package cn.aigc.dit.generate.ark;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ArkAssetSignerTest {

    @Test
    void signsCreateAssetWithVolcengineHmac() {
        byte[] body = """
                {"GroupId":"group-1","URL":"https://cdn.example/a.png","Name":"drama-1-2","AssetType":"Image","ProjectName":"default"}\
                """.getBytes(StandardCharsets.UTF_8);
        URI uri = URI.create("https://ark.cn-beijing.volcengineapi.com/?Action=CreateAsset&Version=2024-01-01");

        ArkAssetSigner.Signed signed = ArkAssetSigner.sign(
                "AKLTTEST", "secret-key", "cn-beijing", "ark", uri, body,
                Instant.parse("2024-10-12T08:00:00Z"));

        assertEquals("20241012T080000Z", signed.xDate());
        assertEquals("8c588f1945272dea521a3309a0f83d2009e119bf07fc5df59c1236c99c4710d1", signed.contentSha256());
        assertEquals("HMAC-SHA256 Credential=AKLTTEST/20241012/cn-beijing/ark/request, "
                + "SignedHeaders=content-type;host;x-content-sha256;x-date, "
                + "Signature=5dfc538e9354b93fb18f75ce50d8ece69192cf446f07520af8ad4ba129660ea8",
                signed.authorization());
    }
}
