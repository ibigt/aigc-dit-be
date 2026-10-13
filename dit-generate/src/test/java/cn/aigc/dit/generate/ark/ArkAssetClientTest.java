package cn.aigc.dit.generate.ark;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.generate.GenerateProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArkAssetClientTest {

    @Test
    void createAssetSignsTheSamePayloadItSends() {
        FakeTransport transport = new FakeTransport();
        transport.body = """
                {"ResponseMetadata":{"RequestId":"r1"},"Result":{"Id":"asset-20240514212750-a3f8k"}}
                """;
        ArkAssetClient client = client(transport);

        String id = client.createAsset(
                "group-1", "https://cdn.example/a.png", "drama-1-2", "default");

        assertEquals("asset-20240514212750-a3f8k", id);
        assertEquals("https://ark.cn-beijing.volcengineapi.com/?Action=CreateAsset&Version=2024-01-01",
                transport.uri.toString());
        assertEquals("{\"GroupId\":\"group-1\",\"URL\":\"https://cdn.example/a.png\",\"Name\":\"drama-1-2\",\"AssetType\":\"Image\",\"ProjectName\":\"default\"}",
                new String(transport.payload, StandardCharsets.UTF_8));
        assertEquals("HMAC-SHA256 Credential=AKLTTEST/20241012/cn-beijing/ark/request, "
                        + "SignedHeaders=content-type;host;x-content-sha256;x-date, "
                        + "Signature=5dfc538e9354b93fb18f75ce50d8ece69192cf446f07520af8ad4ba129660ea8",
                transport.headers.get("authorization"));
        assertEquals("application/json", transport.headers.get("content-type"));
    }

    @Test
    void createGroupSendsAigcFields() {
        FakeTransport transport = new FakeTransport();
        transport.body = """
                {"ResponseMetadata":{"RequestId":"r1"},"Result":{"Id":"group-20241012-aigc"}}
                """;
        ArkAssetClient client = client(transport);

        assertEquals("group-20241012-aigc",
                client.createAssetGroup("aigc-dit", "aigc-dit 虚拟人像", "AIGC", "default"));
        String json = new String(transport.payload, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"Name\":\"aigc-dit\""));
        assertTrue(json.contains("\"Description\":\"aigc-dit 虚拟人像\""));
        assertTrue(json.contains("\"GroupType\":\"AIGC\""));
        assertTrue(transport.uri.toString().contains("Action=CreateAssetGroup"));
    }

    @Test
    void getAssetKeepsFailedErrorText() {
        FakeTransport transport = new FakeTransport();
        transport.body = """
                {"ResponseMetadata":{"RequestId":"r1"},"Result":{"Id":"asset-1","Status":"Failed","Error":{"Code":"InputImageSensitiveContentDetected","Message":"人脸不一致"}}}
                """;
        ArkAssetView view = client(transport).getAsset("asset-1", "default");

        assertTrue(view.failed());
        assertEquals("InputImageSensitiveContentDetected: 人脸不一致", view.errorText());
        assertTrue(transport.uri.toString().contains("Action=GetAsset"));
    }

    @Test
    void httpErrorReturnsArkTextWithoutRewritingRealPerson() {
        FakeTransport transport = new FakeTransport();
        transport.status = 400;
        transport.body = """
                {"ResponseMetadata":{"Error":{"Code":"InputImageSensitiveContentDetected","Message":"参考图包含真人脸"}}}
                """;

        BizException error = assertThrows(BizException.class,
                () -> client(transport).createAsset("group-1", "https://cdn.example/a.png", "drama-1-2", "default"));

        assertEquals("InputImageSensitiveContentDetected: 参考图包含真人脸", error.getMessage());
    }

    @Test
    void deleteIgnoresMissingAsset() {
        FakeTransport transport = new FakeTransport();
        transport.status = 404;
        transport.body = """
                {"ResponseMetadata":{"Error":{"Code":"NotFound.Asset","Message":"素材不存在"}}}
                """;

        client(transport).deleteAsset("asset-gone", "default");
        assertTrue(transport.uri.toString().contains("Action=DeleteAsset"));
    }

    @Test
    void missingCredentialsFailBeforeHttp() {
        GenerateProperties properties = new GenerateProperties();
        ArkAssetClient client = new ArkAssetClient(properties.getArk(), new FakeTransport(), new ObjectMapper(), Clock.systemUTC());

        BizException error = assertThrows(BizException.class,
                () -> client.createAssetGroup("aigc-dit", "aigc-dit 虚拟人像", "AIGC", "default"));
        assertEquals(ArkAssetClient.MISSING_KEY_MESSAGE, error.getMessage());
    }

    @Test
    void storedCredentialOverridesProperties() {
        FakeTransport transport = new FakeTransport();
        transport.body = """
                {"ResponseMetadata":{"RequestId":"r1"},"Result":{"Id":"group-1"}}
                """;
        GenerateProperties properties = new GenerateProperties();
        properties.getArk().setAccessKey("AKLTTEST");
        properties.getArk().setSecretKey("secret-key");
        ArkAssetClient client = new ArkAssetClient(
                properties.getArk(),
                transport,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2024-10-12T08:00:00Z"), ZoneOffset.UTC),
                new ArkAssetCredentialSource() {
                    @Override
                    public String accessKey() {
                        return "STORED_AK";
                    }

                    @Override
                    public String secretKey() {
                        return "stored-secret";
                    }
                });

        client.createAssetGroup("aigc-dit", "aigc-dit 虚拟人像", "AIGC", "default");

        assertTrue(transport.headers.get("authorization").contains("Credential=STORED_AK/"));
        assertFalse(transport.headers.get("authorization").contains("AKLTTEST"));
    }

    private static ArkAssetClient client(FakeTransport transport) {
        GenerateProperties properties = new GenerateProperties();
        properties.getArk().setAccessKey("AKLTTEST");
        properties.getArk().setSecretKey("secret-key");
        properties.getArk().setProjectName("default");
        return new ArkAssetClient(
                properties.getArk(),
                transport,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2024-10-12T08:00:00Z"), ZoneOffset.UTC));
    }

    private static final class FakeTransport implements ArkAssetTransport {
        private URI uri;
        private Map<String, String> headers;
        private byte[] payload;
        private int status = 200;
        private String body = "{}";

        @Override
        public ArkAssetHttpResult post(URI uri, Map<String, String> headers, byte[] body) {
            this.uri = uri;
            this.headers = headers;
            this.payload = body;
            return new ArkAssetHttpResult(status, this.body);
        }
    }
}
