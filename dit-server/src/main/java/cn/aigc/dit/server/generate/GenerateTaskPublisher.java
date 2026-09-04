package cn.aigc.dit.server.generate;

import cn.aigc.dit.generate.GenerateProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class GenerateTaskPublisher {

    private final StringRedisTemplate stringRedisTemplate;
    private final GenerateProperties generateProperties;

    public void publish(Long taskId) {
        String stream = generateProperties.getWorker().getStream();
        MapRecord<String, String, String> record = StreamRecords.mapBacked(Map.of("taskId", String.valueOf(taskId)))
                .withStreamKey(stream);
        stringRedisTemplate.opsForStream().add(record);
        log.info("Enqueued generate task {} to {}", taskId, stream);
    }
}
