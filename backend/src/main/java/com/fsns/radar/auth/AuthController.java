package com.fsns.radar.auth;

import com.fsns.radar.common.ApiException;
import com.fsns.radar.user.User;
import com.fsns.radar.user.UserRepository;
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
 * 소셜 로그인 연동 전 개발 단계에서는 dev-login으로 동일한 토큰 플로우를 검증한다.
 * 카카오/구글 연동 시 이 컨트롤러에 /oauth/{provider} 엔드포인트를 추가하고
 * provider 토큰 검증 후 동일하게 issueTokens()를 타면 된다.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final int MIN_AGE = 14;  // 만 14세 이상 (설계서 1.2)

    private final UserRepository userRepository;
    private final JwtService jwtService;

    public AuthController(UserRepository userRepository, JwtService jwtService) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
    }

    public record DevLoginRequest(
            @NotBlank @Email String email,
            @NotBlank String nickname,
            @NotNull LocalDate birth_date) {}

    public record RefreshRequest(@NotBlank String refresh_token) {}

    @PostMapping("/dev-login")
    public Map<String, Object> devLogin(@Valid @RequestBody DevLoginRequest req) {
        if (Period.between(req.birth_date(), LocalDate.now()).getYears() < MIN_AGE) {
            throw new ApiException(HttpStatus.FORBIDDEN, "만 14세 이상만 가입할 수 있습니다");
        }
        User user = userRepository.findByEmail(req.email())
                .orElseGet(() -> userRepository.save(
                        new User(req.email(), req.nickname(), req.birth_date())));
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
