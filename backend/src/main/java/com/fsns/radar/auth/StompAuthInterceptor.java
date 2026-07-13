package com.fsns.radar.auth;

import java.util.List;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * STOMP CONNECT 프레임의 Authorization 헤더로 WebSocket 세션을 인증한다.
 * HTTP 핸드셰이크(/ws)는 열어두되, 브로커 구독은 유효한 JWT 없이는 불가 —
 * 무인증 구독을 허용하면 아무나 동네 방 피드를 엿볼 수 있다.
 *
 * 클라이언트: CONNECT 시 headers에 {"Authorization": "Bearer <access>"} 전달.
 */
@Component
public class StompAuthInterceptor implements ChannelInterceptor {

    private final JwtService jwtService;

    public StompAuthInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            String header = accessor.getFirstNativeHeader("Authorization");
            Long userId = null;
            if (header != null && header.startsWith("Bearer ")) {
                userId = jwtService.parseUserId(header.substring(7), "access");
            }
            if (userId == null) {
                throw new MessageDeliveryException("WebSocket 인증 실패: 유효한 Bearer 토큰이 필요합니다");
            }
            accessor.setUser(new UsernamePasswordAuthenticationToken(
                    userId, null, List.of()));
        }
        return message;
    }
}
