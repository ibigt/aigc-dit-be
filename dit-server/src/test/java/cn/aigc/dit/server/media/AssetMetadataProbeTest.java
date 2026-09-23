package cn.aigc.dit.server.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AssetMetadataProbeTest {

    @Test
    void probeImageReadsPngHeader() throws IOException {
        BufferedImage image = new BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);

        AssetMetadataProbe.Metadata metadata = AssetMetadataProbe.probeImage(out.toByteArray());

        assertEquals(320, metadata.width());
        assertEquals(200, metadata.height());
        assertNull(metadata.durationMs());
    }

    @Test
    void probeImageParsesWebpLosslessHeader() {
        // VP8L：签名 0x2F 后 4 字节位流，width-1=384(0x180)、height-1=1020(0x3FC)。
        byte[] bytes = webp("VP8L", new byte[]{0x2F, (byte) 0x80, 0x01, (byte) 0xFF, 0x00});

        AssetMetadataProbe.Metadata metadata = AssetMetadataProbe.probeImage(bytes);

        assertEquals(385, metadata.width());
        assertEquals(1021, metadata.height());
    }

    @Test
    void probeImageParsesWebpExtendedHeader() {
        byte[] body = new byte[10];
        body[4] = (byte) 0xE7;
        body[5] = 0x03;
        body[6] = 0x00;
        body[7] = (byte) 0x3F;
        body[8] = 0x01;
        body[9] = 0x00;
        byte[] bytes = webp("VP8X", body);

        AssetMetadataProbe.Metadata metadata = AssetMetadataProbe.probeImage(bytes);

        assertEquals(1000, metadata.width());
        assertEquals(320, metadata.height());
    }

    @Test
    void probeImageParsesWebpLossyHeader() {
        byte[] body = new byte[10];
        // 3 字节 frame tag + 3 字节 sync code（解析不校验），随后宽/高各 2 字节 LE 低 14 位。
        body[6] = (byte) 0x80;
        body[7] = 0x02;
        body[8] = (byte) 0x68;
        body[9] = 0x01;
        byte[] bytes = webp("VP8 ", body);

        AssetMetadataProbe.Metadata metadata = AssetMetadataProbe.probeImage(bytes);

        assertEquals(640, metadata.width());
        assertEquals(360, metadata.height());
    }

    @Test
    void probeImageReturnsEmptyOnGarbage() {
        AssetMetadataProbe.Metadata metadata = AssetMetadataProbe.probeImage(new byte[16]);

        assertNull(metadata.width());
        assertNull(metadata.height());
        assertNull(metadata.durationMs());
    }

    @Test
    void probeMediaToleratesNonMediaFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("probe.txt");
        Files.writeString(file, "not a media file");

        AssetMetadataProbe.Metadata metadata = AssetMetadataProbe.probeMedia(file);

        assertNull(metadata.width());
        assertNull(metadata.height());
        assertNull(metadata.durationMs());
    }

    private static byte[] webp(String chunk, byte[] body) {
        int padded = body.length + (body.length & 1);
        byte[] bytes = new byte[12 + 8 + padded];
        bytes[0] = 'R';
        bytes[1] = 'I';
        bytes[2] = 'F';
        bytes[3] = 'F';
        int riffSize = 4 + 8 + padded;
        bytes[4] = (byte) riffSize;
        bytes[5] = (byte) (riffSize >> 8);
        bytes[6] = (byte) (riffSize >> 16);
        bytes[7] = (byte) (riffSize >> 24);
        bytes[8] = 'W';
        bytes[9] = 'E';
        bytes[10] = 'B';
        bytes[11] = 'P';
        System.arraycopy(chunk.getBytes(StandardCharsets.ISO_8859_1), 0, bytes, 12, 4);
        int length = body.length;
        bytes[16] = (byte) length;
        bytes[17] = (byte) (length >> 8);
        bytes[18] = (byte) (length >> 16);
        bytes[19] = (byte) (length >> 24);
        System.arraycopy(body, 0, bytes, 20, body.length);
        return bytes;
    }
}
