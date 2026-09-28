package cn.aigc.dit.server.media;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageThumbnailsTest {

    @Test
    void normalizeWidthSnapsToBucket() {
        assertNull(ImageThumbnails.normalizeWidth(null));
        assertNull(ImageThumbnails.normalizeWidth(0));
        assertEquals(160, ImageThumbnails.normalizeWidth(80));
        assertEquals(480, ImageThumbnails.normalizeWidth(400));
        assertEquals(1280, ImageThumbnails.normalizeWidth(2000));
    }

    @Test
    void resizeToJpegDownscalesWideImage() throws Exception {
        byte[] png = solidPng(1000, 500);
        ImageThumbnails.Thumb thumb = ImageThumbnails.resizeToJpeg(png, 480);
        assertNotNull(thumb);
        assertEquals("image/jpeg", thumb.contentType());
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(thumb.bytes()));
        assertNotNull(out);
        assertEquals(480, out.getWidth());
        assertEquals(240, out.getHeight());
        assertTrue(thumb.bytes().length > 0);
    }

    private static byte[] solidPng(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, width, height);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
