package com.fsns.radar.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fsns.radar.common.ApiException;
import com.fsns.radar.common.S3UrlSigner;
import com.fsns.radar.wardrobe.WardrobeService;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;

/**
 * 인프라 없이 순수 JVM에서 도는 단위 테스트.
 * 아바타 베이스 파라미터 변경 — 허용값 화이트리스트 검증과
 * "저장 즉시 재생성" 연결 고리를 검증한다.
 */
class UserControllerTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final UserBlockRepository userBlockRepository = mock(UserBlockRepository.class);
    private final S3UrlSigner s3UrlSigner = mock(S3UrlSigner.class);
    private final WardrobeService wardrobeService = mock(WardrobeService.class);

    private final UserController controller = new UserController(
            userRepository, userBlockRepository, s3UrlSigner, wardrobeService);

    private final Authentication auth = mock(Authentication.class);
    private final User user = new User("me@fsns.app", "나", LocalDate.of(2000, 1, 1));

    @BeforeEach
    void setUp() {
        when(auth.getPrincipal()).thenReturn(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    }

    @Test
    void setAvatarConfig_saves_andTriggersRebuild() {
        Map<String, Object> body = controller.setAvatarConfig(auth,
                Map.of("skin", "tan", "hair_style", "long", "hair_color", "pink"));

        assertThat(user.getAvatarConfig())
                .containsEntry("skin", "tan")
                .containsEntry("hair_style", "long");
        assertThat(body).containsKey("avatar_config");
        verify(userRepository).save(user);
        verify(wardrobeService).enqueueAvatarRebuild(user.getId());
    }

    @Test
    void setAvatarConfig_acceptsAuto_andMergePreservesDetectedHair() {
        // 워커가 기록해둔 사진 감지값이 수동 저장으로 지워지면 안 된다
        user.setAvatarConfig(new HashMap<>(Map.of(
                "detected_hair_style", "long", "detected_hair_color", "brown")));

        controller.setAvatarConfig(auth, Map.of("hair_style", "auto", "hair_color", "auto"));

        assertThat(user.getAvatarConfig())
                .containsEntry("hair_style", "auto")
                .containsEntry("detected_hair_style", "long")
                .containsEntry("detected_hair_color", "brown");
    }

    @Test
    void setAvatarConfig_rejectsUnknownKeyOrValue() {
        // 모르는 키 — DB에 임의 JSON이 쌓이는 것을 차단
        assertThatThrownBy(() -> controller.setAvatarConfig(auth, Map.of("height", "tall")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        // 아는 키, 모르는 값
        assertThatThrownBy(() -> controller.setAvatarConfig(auth, Map.of("hair_style", "mohawk")))
                .isInstanceOf(ApiException.class);
        verify(wardrobeService, never()).enqueueAvatarRebuild(anyLong());
    }
}
