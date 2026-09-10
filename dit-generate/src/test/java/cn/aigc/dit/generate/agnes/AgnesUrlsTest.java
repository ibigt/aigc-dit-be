package cn.aigc.dit.generate.agnes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgnesUrlsTest {

    @Test
    void appendsV1WhenMissing() {
        assertEquals("https://api.agnes-ai.cn/v1",
                AgnesUrls.v1Base("https://api.agnes-ai.cn", "x"));
        assertEquals("https://api.agnes-ai.cn/v1",
                AgnesUrls.v1Base("https://api.agnes-ai.cn/v1/", "x"));
        assertEquals("https://api.agnes-ai.cn/v1",
                AgnesUrls.v1Base("https://api.agnes-ai.cn/v1/videos", "x"));
    }

    @Test
    void buildsCreateAndPollUrls() {
        String v1 = "https://api.agnes-ai.cn/v1";
        assertEquals("https://api.agnes-ai.cn/v1/images/generations", AgnesUrls.imagesGenerations(v1));
        assertEquals("https://api.agnes-ai.cn/v1/videos", AgnesUrls.videos(v1));
        assertEquals("https://api.agnes-ai.cn/v1/videos/task_1", AgnesUrls.videoRetrieve(v1, "task_1"));
        assertEquals("https://api.agnes-ai.cn/agnesapi?video_id=video_1&model_name=agnes-video-2.5-flash",
                AgnesUrls.poll(v1, "video_1", "agnes-video-2.5-flash").toString());
    }
}
