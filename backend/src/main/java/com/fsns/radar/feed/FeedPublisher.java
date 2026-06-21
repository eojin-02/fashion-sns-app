package com.fsns.radar.feed;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** 동네 방 이벤트를 Redis Pub/Sub 채널(feed:{dong_code})로 발행한다. */
@Component
public class FeedPublisher {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public FeedPublisher(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public void publish(String dongCode, Map<String, Object> event) {
        try {
            redis.convertAndSend("feed:" + dongCode, objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
