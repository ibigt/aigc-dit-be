package cn.aigc.dit.generate.sglang;

import cn.aigc.dit.common.exception.BizException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class JdkSglangHttpTest {
    @Test void preservesBearerForJsonAndBinaryDownloadsAndAllowsNoKey() throws Exception {
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        byte[] mp4 = "mp4".getBytes(StandardCharsets.UTF_8);
        when(response.body()).thenReturn(mp4);
        when(client.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any())).thenReturn(response);
        JdkSglangHttp http = new JdkSglangHttp(client);
        String base = "http://localhost:30010/v1";
        http.request("POST", URI.create(base + "/videos"), "secret", "{}");
        assertArrayEquals(mp4, http.request("GET", URI.create(base + "/videos/v/content"), "secret", null));
        http.request("GET", URI.create(base + "/models"), null, null);
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client, times(3)).send(captor.capture(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
        var requests = captor.getAllValues();
        assertEquals(java.time.Duration.ofHours(1), requests.get(0).timeout().orElseThrow());
        assertEquals(java.time.Duration.ofSeconds(600), requests.get(1).timeout().orElseThrow());
        assertEquals(java.time.Duration.ofSeconds(60), requests.get(2).timeout().orElseThrow());
        for (HttpRequest request : requests) {
            assertEquals(HttpClient.Version.HTTP_1_1, request.version().orElseThrow());
        }
        assertEquals("POST", requests.get(0).method());
        assertEquals("application/json", requests.get(0).headers().firstValue("Content-Type").orElseThrow());
        assertEquals("Bearer secret", requests.get(0).headers().firstValue("Authorization").orElseThrow());
        assertEquals("Bearer secret", requests.get(1).headers().firstValue("Authorization").orElseThrow());
        assertEquals("GET", requests.get(1).method());
        assertTrue(requests.get(2).headers().firstValue("Authorization").isEmpty());
        when(response.statusCode()).thenReturn(401);
        assertThrows(BizException.class, () -> http.request("GET", URI.create(base + "/models"), null, null));
    }
}
