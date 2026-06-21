package com.fsns.radar.feed;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * Redis Pub/Sub → STOMP 릴레이 (설계서 2.1).
 * 서버 인스턴스가 여러 대여도 Redis 채널을 거치므로 모든 인스턴스의
 * WebSocket 구독자에게 피드가 전파된다.
 */
@Configuration
public class FeedRelay {

    @Bean
    public RedisMessageListenerContainer feedListenerContainer(
            RedisConnectionFactory connectionFactory,
            SimpMessagingTemplate messagingTemplate) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) -> {
            String channel = new String(message.getChannel());
            String dongCode = channel.substring("feed:".length());
            String body = new String(message.getBody());
            messagingTemplate.convertAndSend("/topic/room/" + dongCode, body);
        }, new PatternTopic("feed:*"));

        // AI 워커의 스캔 완료 알림 (설계서 4.1 ④ — 완료 시 WebSocket 통지)
        container.addMessageListener((message, pattern) -> {
            String channel = new String(message.getChannel());
            String userId = channel.substring("scan-result:".length());
            String body = new String(message.getBody());
            messagingTemplate.convertAndSend("/topic/scan/" + userId, body);
        }, new PatternTopic("scan-result:*"));
        return container;
    }
}
