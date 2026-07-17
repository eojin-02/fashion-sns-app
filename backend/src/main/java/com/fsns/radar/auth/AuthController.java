package com.fsns.radar.auth;

import com.fsns.radar.common.ApiException;
import com.fsns.radar.user.User;
import com.fsns.radar.user.UserRepository;
import com.fsns.radar.wardrobe.WardrobeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.Period;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 설계서 4.0: OAuth2 소셜 로그인(카카오/구글) → 자체 JWT 발급.
 * 구글은 /oauth/google로 연동 완료. 카카오 연동 시 같은 패턴으로
 * KakaoTokenVerifier + /oauth/kakao를 추가하고 동일하게 issueTokens()를 타면 된다.
 * dev-login은 소셜 설정 없는 로컬 개발용으로 유지한다.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final int MIN_AGE = 14;  // 만 14세 이상 (설계서 1.2)

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final GoogleTokenVerifier googleTokenVerifier;
    private final WardrobeService wardrobeService;

    public AuthController(UserRepository userRepository, JwtService jwtService,
                          GoogleTokenVerifier googleTokenVerifier,
                          WardrobeService wardrobeService) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.googleTokenVerifier = googleTokenVerifier;
        this.wardrobeService = wardrobeService;
    }

    public record DevLoginRequest(
            @NotBlank @Email String email,
            @NotBlank String nickname,
            @NotNull LocalDate birth_date) {}

    public record RefreshRequest(@NotBlank String refresh_token) {}

    /** nickname/birth_date는 신규 가입 2단계 호출에서만 채워진다 */
    public record GoogleLoginRequest(
            @NotBlank String id_token,
            String nickname,
            LocalDate birth_date) {}

    /**
     * 구글 ID 토큰 → 자체 JWT.
     * 신규 유저는 만 14세 검증에 필요한 생년월일을 구글이 주지 않으므로,
     * signup_required를 반환하고 앱이 닉네임/생년월일을 받아 같은 토큰으로 재호출한다.
     */
    @PostMapping("/oauth/google")
    public Map<String, Object> googleLogin(@Valid @RequestBody GoogleLoginRequest req) {
        GoogleTokenVerifier.GoogleIdentity identity = googleTokenVerifier.verify(req.id_token());

        User existing = userRepository.findByEmail(identity.email()).orElse(null);
        if (existing != null) {
            return issueTokens(existing.getId());
        }
        if (req.birth_date() == null) {
            return Map.of(
                    "signup_required", true,
                    "email", identity.email(),
                    "suggested_nickname", identity.nickname() == null ? "" : identity.nickname());
        }
        if (Period.between(req.birth_date(), LocalDate.now()).getYears() < MIN_AGE) {
            throw new ApiException(HttpStatus.FORBIDDEN, "만 14세 이상만 가입할 수 있습니다");
        }
        String nickname = req.nickname() != null && !req.nickname().isBlank()
                ? req.nickname()
                : (identity.nickname() == null || identity.nickname().isBlank()
                        ? "새 유저" : identity.nickname());
        User user = new User(identity.email(), nickname, req.birth_date());
        user.setAvatarUrl(identity.avatarUrl());
        Long userId = userRepository.save(user).getId();
        // 가입 시 1회 — 기본 파라미터로 첫 아바타 생성 (커스터마이징 화면에서 즉시 수정 가능)
        wardrobeService.enqueueAvatarRebuild(userId);
        return issueTokens(userId);
    }

    @PostMapping("/dev-login")
    public Map<String, Object> devLogin(@Valid @RequestBody DevLoginRequest req) {
        if (Period.between(req.birth_date(), LocalDate.now()).getYears() < MIN_AGE) {
            throw new ApiException(HttpStatus.FORBIDDEN, "만 14세 이상만 가입할 수 있습니다");
        }
        User user = userRepository.findByEmail(req.email()).orElse(null);
        if (user == null) {
            user = userRepository.save(new User(req.email(), req.nickname(), req.birth_date()));
            // 가입 시 1회 — 기본 파라미터로 첫 아바타 생성 (옷장이 비어도 기본 착장으로 만들어진다)
            wardrobeService.enqueueAvatarRebuild(user.getId());
        }
        return issueTokens(user.getId());
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh(@Valid @RequestBody RefreshRequest req) {
        Long userId = jwtService.parseUserId(req.refresh_token(), "refresh");
        if (userId == null || !userRepository.existsById(userId)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "유효하지 않은 리프레시 토큰입니다");
        }
        return issueTokens(userId);
    }

    private Map<String, Object> issueTokens(long userId) {
        return Map.of(
                "access_token", jwtService.createAccessToken(userId),
                "refresh_token", jwtService.createRefreshToken(userId),
                "token_type", "Bearer");
    }
}
