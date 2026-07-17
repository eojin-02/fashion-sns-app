package com.fsns.radar.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fsns.radar.auth.GoogleTokenVerifier.GoogleIdentity;
import com.fsns.radar.common.ApiException;
import com.fsns.radar.user.User;
import com.fsns.radar.user.UserRepository;
import com.fsns.radar.wardrobe.WardrobeService;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

/**
 * 인프라 없이 순수 JVM에서 도는 단위 테스트.
 * 구글 로그인 가입 플로우(설계서 4.0) — 기존 유저 즉시 로그인 / 신규 유저 2단계 가입 /
 * 만 14세 미만 차단(설계서 1.2)을 검증한다. 토큰 서명 검증은 GoogleTokenVerifierTest 몫.
 */
class AuthControllerTest {

    private static final String ID_TOKEN = "google-id-token";
    private static final GoogleIdentity IDENTITY =
            new GoogleIdentity("user@gmail.com", "구글유저", "https://lh3.example/pic.jpg");

    private final UserRepository userRepository = mock(UserRepository.class);
    private final JwtService jwtService = mock(JwtService.class);
    private final GoogleTokenVerifier verifier = mock(GoogleTokenVerifier.class);
    private final WardrobeService wardrobeService = mock(WardrobeService.class);
    private final AuthController controller =
            new AuthController(userRepository, jwtService, verifier, wardrobeService);

    private AuthController.GoogleLoginRequest request(String nickname, LocalDate birthDate) {
        return new AuthController.GoogleLoginRequest(ID_TOKEN, nickname, birthDate);
    }

    @Test
    void existingUser_logsInImmediately_withoutProfileFields() {
        when(verifier.verify(ID_TOKEN)).thenReturn(IDENTITY);
        User existing = mock(User.class);
        when(existing.getId()).thenReturn(42L);
        when(userRepository.findByEmail("user@gmail.com")).thenReturn(Optional.of(existing));
        when(jwtService.createAccessToken(42L)).thenReturn("access");
        when(jwtService.createRefreshToken(42L)).thenReturn("refresh");

        Map<String, Object> body = controller.googleLogin(request(null, null));

        assertThat(body).containsEntry("access_token", "access")
                .containsEntry("refresh_token", "refresh");
        verify(userRepository, never()).save(any());
        verify(wardrobeService, never()).enqueueAvatarRebuild(anyLong());  // 로그인은 재생성 없음
    }

    @Test
    void newUser_withoutBirthDate_getsSignupRequired() {
        when(verifier.verify(ID_TOKEN)).thenReturn(IDENTITY);
        when(userRepository.findByEmail("user@gmail.com")).thenReturn(Optional.empty());

        Map<String, Object> body = controller.googleLogin(request(null, null));

        assertThat(body).containsEntry("signup_required", true)
                .containsEntry("suggested_nickname", "구글유저");
        verify(userRepository, never()).save(any());
        verify(jwtService, never()).createAccessToken(anyLong());
    }

    @Test
    void newUser_underFourteen_isRejected() {
        when(verifier.verify(ID_TOKEN)).thenReturn(IDENTITY);
        when(userRepository.findByEmail("user@gmail.com")).thenReturn(Optional.empty());

        LocalDate thirteenYearsOld = LocalDate.now().minusYears(13);
        assertThatThrownBy(() -> controller.googleLogin(request("닉", thirteenYearsOld)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN));
        verify(userRepository, never()).save(any());
    }

    @Test
    void newUser_withProfile_isSavedAndLoggedIn() {
        when(verifier.verify(ID_TOKEN)).thenReturn(IDENTITY);
        when(userRepository.findByEmail("user@gmail.com")).thenReturn(Optional.empty());
        User saved = mock(User.class);
        when(saved.getId()).thenReturn(7L);
        when(userRepository.save(any(User.class))).thenReturn(saved);
        when(jwtService.createAccessToken(7L)).thenReturn("access");
        when(jwtService.createRefreshToken(7L)).thenReturn("refresh");

        Map<String, Object> body =
                controller.googleLogin(request("패션왕", LocalDate.of(2000, 1, 1)));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("user@gmail.com");
        assertThat(captor.getValue().getNickname()).isEqualTo("패션왕");  // 입력 닉네임 우선
        assertThat(captor.getValue().getAvatarUrl()).isEqualTo("https://lh3.example/pic.jpg");
        assertThat(body).containsEntry("access_token", "access");
        verify(wardrobeService).enqueueAvatarRebuild(7L);  // 가입 시 1회 — 첫 아바타 생성
    }

    @Test
    void newUser_withBlankNickname_fallsBackToGoogleName() {
        when(verifier.verify(ID_TOKEN)).thenReturn(IDENTITY);
        when(userRepository.findByEmail("user@gmail.com")).thenReturn(Optional.empty());
        User saved = mock(User.class);
        when(saved.getId()).thenReturn(8L);
        when(userRepository.save(any(User.class))).thenReturn(saved);
        when(jwtService.createAccessToken(8L)).thenReturn("access");
        when(jwtService.createRefreshToken(8L)).thenReturn("refresh");

        controller.googleLogin(request("  ", LocalDate.of(2000, 1, 1)));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getNickname()).isEqualTo("구글유저");
    }
}
