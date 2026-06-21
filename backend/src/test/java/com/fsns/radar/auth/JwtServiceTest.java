package com.fsns.radar.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * 인프라(DB/Redis) 없이 순수 JVM에서 도는 단위 테스트.
 * 설계서 4.0 JWT 발급/검증 로직과 5장 토큰 위변조 방어를 실제로 실행해 검증한다.
 */
class JwtServiceTest {

    private static final String SECRET = Base64.getEncoder()
            .encodeToString("fsns-test-secret-key-at-least-32-bytes-long-xx".getBytes());

    private final JwtService jwt = new JwtService(SECRET, 1800, 1209600);

    @Test
    void accessToken_roundTrips_toSameUserId() {
        String token = jwt.createAccessToken(42L);
        assertThat(jwt.parseUserId(token, "access")).isEqualTo(42L);
    }

    @Test
    void refreshToken_roundTrips_toSameUserId() {
        String token = jwt.createRefreshToken(7L);
        assertThat(jwt.parseUserId(token, "refresh")).isEqualTo(7L);
    }

    @Test
    void accessToken_isRejected_whenExpectingRefresh() {
        // access 토큰으로 refresh 흐름을 타려는 시도는 막혀야 한다
        String access = jwt.createAccessToken(1L);
        assertThat(jwt.parseUserId(access, "refresh")).isNull();
    }

    @Test
    void tamperedToken_isRejected() {
        String token = jwt.createAccessToken(1L);
        String tampered = token.substring(0, token.length() - 2)
                + (token.endsWith("a") ? "b" : "a");
        assertThat(jwt.parseUserId(tampered, "access")).isNull();
    }

    @Test
    void tokenSignedWithOtherSecret_isRejected() {
        // 다른 시크릿으로 서명된 토큰은 우리 서버에서 무효 (위조 방어)
        String otherSecret = Base64.getEncoder()
                .encodeToString("totally-different-secret-key-32bytes-min!!".getBytes());
        JwtService attacker = new JwtService(otherSecret, 1800, 1209600);
        String forged = attacker.createAccessToken(999L);
        assertThat(jwt.parseUserId(forged, "access")).isNull();
    }

    @Test
    void garbageString_isRejected() {
        assertThat(jwt.parseUserId("not.a.jwt", "access")).isNull();
    }
}
