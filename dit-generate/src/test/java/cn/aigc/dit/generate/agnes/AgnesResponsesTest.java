package cn.aigc.dit.generate.agnes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgnesResponsesTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void prefersVideoIdAndIgnoresTaskPrefix() throws Exception {
        JsonNode root = mapper.readTree("""
                {"id":"task_1","task_id":"task_1","video_id":"video_9","status":"queued"}
                """);
        assertEquals("video_9", AgnesResponses.videoId(root));
        assertEquals("task_1", AgnesResponses.taskId(root));
        assertEquals("video_9", AgnesResponses.pollId(root));
    }

    @Test
    void doesNotTreatTaskIdAsVideoId() throws Exception {
        JsonNode root = mapper.readTree("""
                {"id":"task_only","status":"queued"}
                """);
        assertEquals("", AgnesResponses.videoId(root));
        assertEquals("task_only", AgnesResponses.taskId(root));
    }

    @Test
    void readsNestedVideoId() throws Exception {
        JsonNode root = mapper.readTree("""
                {"id":"task_1","video":{"id":"video_nested"}}
                """);
        assertEquals("video_nested", AgnesResponses.videoId(root));
    }

    @Test
    void readsOutputUrlFromMetadata() throws Exception {
        JsonNode root = mapper.readTree("""
                {"status":"completed","metadata":{"url":"https://cdn.example/out.mp4"}}
                """);
        assertEquals("https://cdn.example/out.mp4", AgnesResponses.outputUrl(root));
        assertEquals("completed", AgnesResponses.status(root));
    }
}
