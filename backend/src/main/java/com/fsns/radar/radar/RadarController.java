package com.fsns.radar.radar;

import com.fsns.radar.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/radar")
public class RadarController {

    private final RadarService radarService;
    private final StringRedisTemplate redis;

    public RadarController(RadarService radarService, StringRedisTemplate redis) {
        this.radarService = radarService;
        this.redis = redis;
    }

    public record ResolveRequest(@NotNull List<String> scanned_tokens) {}

    /** 설계서 4.3 — 익명성 재설계의 핵심. 응답에 user_id는 절대 포함되지 않는다. */
    @PostMapping("/resolve")
    public Map<String, Object> resolve(Authentication auth, @Valid @RequestBody ResolveRequest req) {
        Long userId = (Long) auth.getPrincipal();
        return Map.of("nearby", radarService.resolve(userId, req.scanned_tokens()));
    }

    /** 설계서 4.4 — 토큰 리프레시 (10분 주기, MAC 랜덤화와 비동기화는 클라이언트 타이머 책임) */
    @PostMapping("/token/refresh")
    public Map<String, Object> refresh(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        String dongCode = redis.opsForValue().get("lastroom:dong:" + userId);
        if (dongCode == null) {
            throw new ApiException(HttpStatus.CONFLICT, "먼저 방에 입장해야 합니다");
        }
        var issued = radarService.issueToken(userId, dongCode);
        return Map.of(
                "my_ble_token", issued.token(),
                "token_expires_in_sec", issued.expiresInSeconds());
    }
}
