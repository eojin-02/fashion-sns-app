package com.fsns.radar.auth;

import com.fsns.radar.common.ApiException;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;

/**
 * 설계서 4.0 — 구글 ID 토큰 검증.
 * 구글 공개키(JWKS)로 서명을 확인하고 iss/aud/exp를 검증한 뒤,
 * 자체 JWT 발급에 필요한 최소 신원(이메일·이름·프로필 사진)만 돌려준다.
 * 네트워크로 provider 토큰을 검증하는 유일한 지점 — 카카오 연동 시 같은 패턴으로 클래스 추가.
 */
@Service
public class GoogleTokenVerifier {

    static final String JWKS_URI = "https://www.googleapis.com/oauth2/v3/certs";
    /** 구글은 iss를 두 표기로 발급한다 (https://developers.google.com/identity/openid-connect) */
    private static final Set<String> GOOGLE_ISSUERS =
            Set.of("https://accounts.google.com", "accounts.google.com");

    private final String clientId;
    private volatile JwtDecoder decoder;

    public record GoogleIdentity(String email, String nickname, String avatarUrl) {}

    public GoogleTokenVerifier(@Value("${app.google.client-id}") String clientId) {
        this.clientId = clientId;
    }

    public GoogleIdentity verify(String idToken) {
        if (clientId == null || clientId.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "구글 로그인이 설정되지 않았습니다 (APP_GOOGLE_CLIENT_ID)");
        }
        Jwt jwt;
        try {
            jwt = decoder().decode(idToken);
        } catch (JwtException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "유효하지 않은 구글 토큰입니다");
        }
        String email = jwt.getClaimAsString("email");
        if (email == null || !Boolean.TRUE.equals(jwt.getClaim("email_verified"))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "이메일이 확인되지 않은 구글 계정입니다");
        }
        return new GoogleIdentity(email,
                jwt.getClaimAsString("name"), jwt.getClaimAsString("picture"));
    }

    /** JWKS 조회는 네트워크가 필요하므로 첫 로그인 시점까지 지연 생성 */
    private JwtDecoder decoder() {
        JwtDecoder local = decoder;
        if (local == null) {
            synchronized (this) {
                if (decoder == null) {
                    NimbusJwtDecoder built = NimbusJwtDecoder.withJwkSetUri(JWKS_URI).build();
                    built.setJwtValidator(claimsValidator(clientId));
                    decoder = built;
                }
                local = decoder;
            }
        }
        return local;
    }

    /** exp/nbf + iss(구글 두 표기) + aud(우리 클라이언트 ID) 검증 */
    static OAuth2TokenValidator<Jwt> claimsValidator(String clientId) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(),
                new JwtClaimValidator<Object>(JwtClaimNames.ISS,
                        iss -> iss != null && GOOGLE_ISSUERS.contains(iss.toString())),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        aud -> aud != null && aud.contains(clientId)));
    }
}
