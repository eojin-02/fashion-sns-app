package com.fsns.radar.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fsns.radar.common.ApiException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 인프라 없이 순수 JVM에서 도는 단위 테스트.
 * 구글 ID 토큰의 iss/aud/exp 클레임 검증(설계서 4.0)이 실제로 위조 토큰을 걸러내는지 확인한다.
 * 서명(JWKS) 검증은 Nimbus 몫이므로 여기서는 클레임 규칙만 검증 대상이다.
 */
class GoogleTokenVerifierTest {

    private static final String CLIENT_ID = "test-client.apps.googleusercontent.com";

    private static Jwt jwt(String iss, List<String> aud) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .audience(aud);
        if (iss != null) {
            builder.claim("iss", iss);
        }
        return builder.build();
    }

    @Test
    void validGoogleToken_passes_withBothIssuerForms() {
        assertThat(GoogleTokenVerifier.claimsValidator(CLIENT_ID)
                .validate(jwt("https://accounts.google.com", List.of(CLIENT_ID)))
                .hasErrors()).isFalse();
        assertThat(GoogleTokenVerifier.claimsValidator(CLIENT_ID)
                .validate(jwt("accounts.google.com", List.of(CLIENT_ID)))
                .hasErrors()).isFalse();
    }

    @Test
    void foreignIssuer_isRejected() {
        // 다른 IdP가 발급한 토큰으로 로그인 시도 → 차단
        assertThat(GoogleTokenVerifier.claimsValidator(CLIENT_ID)
                .validate(jwt("https://evil.example.com", List.of(CLIENT_ID)))
                .hasErrors()).isTrue();
    }

    @Test
    void tokenForAnotherApp_isRejected() {
        // 구글이 발급했더라도 다른 앱(aud 불일치)용 토큰은 무효 — 토큰 재사용 공격 방어
        assertThat(GoogleTokenVerifier.claimsValidator(CLIENT_ID)
                .validate(jwt("https://accounts.google.com", List.of("other-app.apps.googleusercontent.com")))
                .hasErrors()).isTrue();
    }

    @Test
    void missingIssuer_isRejected() {
        assertThat(GoogleTokenVerifier.claimsValidator(CLIENT_ID)
                .validate(jwt(null, List.of(CLIENT_ID)))
                .hasErrors()).isTrue();
    }

    @Test
    void unconfiguredClientId_returns503_insteadOfCallingGoogle() {
        GoogleTokenVerifier verifier = new GoogleTokenVerifier("");
        assertThatThrownBy(() -> verifier.verify("any-token"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }
}
