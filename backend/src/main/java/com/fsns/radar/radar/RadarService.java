package com.fsns.radar.radar;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fsns.radar.common.ApiException;
import com.fsns.radar.user.User;
import com.fsns.radar.user.UserBlockRepository;
import com.fsns.radar.user.UserRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 설계서 2.2 — "토큰 매핑은 서버 밖으로 나가지 않는다."
 *
 * 토큰 ↔ 유저 매핑은 오직 Redis에만 존재한다 (RDB 미영속 — 사후 유출 시에도
 * 과거 동선 복원 불가). 클라이언트는 자기 토큰만 알고, resolve 응답은
 * 영속 user_id가 아닌 세션 스코프 session_avatar_id만 담는다.
 */
@Service
public class RadarService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_TOKENS_PER_RESOLVE = 50;

    private final StringRedisTemplate redis;
    private final UserRepository userRepository;
    private final UserBlockRepository userBlockRepository;
    private final ObjectMapper objectMapper;
    private final Duration tokenTtl;
    private final Duration resolveCooldown;

    public RadarService(StringRedisTemplate redis,
                        UserRepository userRepository,
                        UserBlockRepository userBlockRepository,
                        ObjectMapper objectMapper,
                        @Value("${app.radar.token-ttl-seconds}") long tokenTtlSeconds,
                        @Value("${app.radar.resolve-cooldown-seconds}") long resolveCooldownSeconds) {
        this.redis = redis;
        this.userRepository = userRepository;
        this.userBlockRepository = userBlockRepository;
        this.objectMapper = objectMapper;
        this.tokenTtl = Duration.ofSeconds(tokenTtlSeconds);
        this.resolveCooldown = Duration.ofSeconds(resolveCooldownSeconds);
    }

    /** Redis에만 존재하는 토큰 세션. user_id는 서버 내부에서만 쓰이고 절대 응답에 포함되지 않는다. */
    public record TokenSession(Long userId, String sessionAvatarId, String dongCode) {}

    public record NearbyAvatar(String session_avatar_id, boolean highlight) {}

    public record IssuedToken(String token, long expiresInSeconds) {}

    /** 방 입장/리프레시 시 호출. 이전 토큰 매핑은 즉시 무효화한다. */
    public IssuedToken issueToken(Long userId, String dongCode) {
        String old = redis.opsForValue().get("mytoken:" + userId);
        if (old != null) {
            redis.delete("token:" + old);
        }
        String token = "tk_" + randomHex(12);
        TokenSession session = new TokenSession(userId, sessionAvatarId(userId, dongCode), dongCode);
        redis.opsForValue().set("token:" + token, toJson(session), tokenTtl);
        redis.opsForValue().set("mytoken:" + userId, token, tokenTtl);
        return new IssuedToken(token, tokenTtl.toSeconds());
    }

    /**
     * 세션 스코프 아바타 ID. 영속 user_id를 노출하면 세션을 넘나드는
     * 재식별(re-identification)이 가능해지므로 방 세션 단위로만 유효하다.
     */
    public String sessionAvatarId(Long userId, String dongCode) {
        String key = "sa:" + userId + ":" + dongCode;
        String existing = redis.opsForValue().get(key);
        if (existing != null) {
            return existing;
        }
        String sa = "sa_" + randomHex(8);
        redis.opsForValue().set(key, sa, Duration.ofHours(2));
        // 갤러리 응답 조립용 역방향 매핑 (서버 내부 전용)
        redis.opsForValue().set("sa-rev:" + sa, String.valueOf(userId), Duration.ofHours(2));
        return sa;
    }

    /** 설계서 4.3 — 스캔된 토큰 목록을 아바타 카드로 해석. Rate Limit 3분/회. */
    public List<NearbyAvatar> resolve(Long userId, List<String> scannedTokens) {
        Boolean acquired = redis.opsForValue()
                .setIfAbsent("ratelimit:resolve:" + userId, "1", resolveCooldown);
        if (Boolean.FALSE.equals(acquired)) {
            // 쿨타임 미적용 시 방향 벡터 추적(금속탐지기식 미행)이 가능해진다 (설계서 5)
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "resolve 쿨타임이 지나지 않았습니다");
        }

        Set<Long> excluded = userBlockRepository.findAllRelatedUserIds(userId);
        List<TokenSession> sessions = scannedTokens.stream()
                .distinct()
                .limit(MAX_TOKENS_PER_RESOLVE)
                .map(t -> redis.opsForValue().get("token:" + t))
                .filter(json -> json != null)
                .map(this::fromJson)
                .filter(s -> !s.userId().equals(userId))
                .filter(s -> !excluded.contains(s.userId()))
                .toList();

        // radar_visible=false(고스트 모드) 유저 제외
        Map<Long, User> users = userRepository
                .findAllById(sessions.stream().map(TokenSession::userId).toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));

        List<NearbyAvatar> result = new ArrayList<>();
        for (TokenSession s : sessions) {
            User u = users.get(s.userId());
            if (u != null && u.isRadarVisible()) {
                result.add(new NearbyAvatar(s.sessionAvatarId(), true));  // Tier 1 = 홀로그램 강조
            }
        }
        return result;
    }

    public Long resolveSessionAvatarToUserId(String sessionAvatarId) {
        String uid = redis.opsForValue().get("sa-rev:" + sessionAvatarId);
        return uid == null ? null : Long.valueOf(uid);
    }

    private static String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }

    private String toJson(TokenSession session) {
        try {
            return objectMapper.writeValueAsString(session);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private TokenSession fromJson(String json) {
        try {
            return objectMapper.readValue(json, TokenSession.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
