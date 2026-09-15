package cn.aigc.dit.server.media;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MediaUrlsAudioTest {

    @Test
    void resolvesAudioContentTypes() {
        assertEquals("audio/mpeg", MediaUrls.contentType("voice.mp3", null));
        assertEquals("audio/wav", MediaUrls.contentType("voice.wav", null));
        assertEquals("audio/aac", MediaUrls.contentType("voice.aac", null));
        assertEquals("audio/flac", MediaUrls.contentType("voice.flac", null));
        assertEquals("audio/opus", MediaUrls.contentType("voice.opus", null));
        assertEquals("audio/L16", MediaUrls.contentType("voice.pcm", null));
    }
}
