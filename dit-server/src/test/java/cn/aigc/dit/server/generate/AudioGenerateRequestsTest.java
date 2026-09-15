package cn.aigc.dit.server.generate;

import cn.aigc.dit.generate.GenerateRequest;
import cn.aigc.dit.server.entity.GenTask;
import cn.aigc.dit.server.model.req.GenerateSubmitReq;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AudioGenerateRequestsTest {

    @Test
    void audioParametersRoundTripThroughPersistedTask() {
        GenerateSubmitReq submitted = new GenerateSubmitReq();
        submitted.setCategory("audio");
        submitted.setPrompt("欢迎使用语音创作");
        submitted.setVoiceId("nova");
        submitted.setSpeed(1.2);
        submitted.setAudioFormat("wav");
        GenerateRequest request = GenerateRequests.fromSubmit(7L, "tts-pro", submitted, null);
        request.setAdapterKey("openai-speech");

        Map<String, Object> params = GenerateRequests.toParams(request, 11L);
        GenTask task = new GenTask();
        task.setId(9L);
        task.setUserId(7L);
        task.setCategory("audio");
        task.setPrompt(request.getPrompt());
        task.setCopies(1);
        task.setParams(params);

        GenerateRequest restored = GenerateRequests.fromTask(task, "tts-pro");
        assertEquals("t2a", restored.getMode());
        assertEquals("nova", restored.getVoiceId());
        assertEquals(1.2, restored.getSpeed());
        assertEquals("wav", restored.getAudioFormat());
        assertEquals("openai-speech", restored.getAdapterKey());
    }
}
