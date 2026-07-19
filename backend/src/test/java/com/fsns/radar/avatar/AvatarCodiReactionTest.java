package com.fsns.radar.avatar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fsns.radar.codi.CodiComment;
import com.fsns.radar.codi.CodiCommentRepository;
import com.fsns.radar.codi.CodiLike;
import com.fsns.radar.codi.CodiLikeRepository;
import com.fsns.radar.codi.DailyCodi;
import com.fsns.radar.codi.DailyCodiItemRepository;
import com.fsns.radar.codi.DailyCodiRepository;
import com.fsns.radar.common.ApiException;
import com.fsns.radar.common.S3UrlSigner;
import com.fsns.radar.radar.RadarService;
import com.fsns.radar.user.User;
import com.fsns.radar.user.UserBlockRepository;
import com.fsns.radar.user.UserReportRepository;
import com.fsns.radar.user.UserRepository;
import com.fsns.radar.wardrobe.ClothesItemRepository;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;

/**
 * 인프라 없이 순수 JVM에서 도는 단위 테스트.
 * 코디 반응(좋아요/댓글)의 핵심 규칙 — 자기 자신 차단, 댓글 쿨다운(도배 방지),
 * 익명성(응답에 작성자 user_id 미노출)을 검증한다.
 */
class AvatarCodiReactionTest {

    private static final Long VIEWER = 1L;
    private static final Long TARGET = 2L;
    private static final String SA_ID = "sa_target";

    private final RadarService radarService = mock(RadarService.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final UserBlockRepository userBlockRepository = mock(UserBlockRepository.class);
    private final UserReportRepository userReportRepository = mock(UserReportRepository.class);
    private final DailyCodiRepository dailyCodiRepository = mock(DailyCodiRepository.class);
    private final DailyCodiItemRepository dailyCodiItemRepository = mock(DailyCodiItemRepository.class);
    private final ClothesItemRepository clothesItemRepository = mock(ClothesItemRepository.class);
    private final S3UrlSigner s3UrlSigner = mock(S3UrlSigner.class);
    private final CodiLikeRepository codiLikeRepository = mock(CodiLikeRepository.class);
    private final CodiCommentRepository codiCommentRepository = mock(CodiCommentRepository.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);

    private final AvatarService service = new AvatarService(
            radarService, userRepository, userBlockRepository, userReportRepository,
            dailyCodiRepository, dailyCodiItemRepository, clothesItemRepository,
            s3UrlSigner, codiLikeRepository, codiCommentRepository, redis);

    private final DailyCodi targetCodi = mock(DailyCodi.class);

    @BeforeEach
    void setUp() {
        when(radarService.resolveSessionAvatarToUserId(SA_ID)).thenReturn(TARGET);
        User target = mock(User.class);
        when(target.getId()).thenReturn(TARGET);
        when(target.isRadarVisible()).thenReturn(true);
        when(userRepository.findById(TARGET)).thenReturn(Optional.of(target));
        when(userBlockRepository.findAllRelatedUserIds(VIEWER)).thenReturn(Set.of());
        when(targetCodi.getId()).thenReturn(5L);
        when(targetCodi.getUserId()).thenReturn(TARGET);
        when(dailyCodiRepository.findByUserId(TARGET)).thenReturn(Optional.of(targetCodi));
        when(redis.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void likeCodi_savesLikeForOthersOutfit() {
        service.likeCodi(VIEWER, SA_ID);
        verify(codiLikeRepository).save(any(CodiLike.class));
    }

    @Test
    void likeCodi_ownOutfit_isRejected() {
        // 세션 아바타가 본인을 가리키는 경우 (자기 코디 셀프 좋아요 시도)
        when(targetCodi.getUserId()).thenReturn(VIEWER);
        User self = mock(User.class);
        when(self.getId()).thenReturn(VIEWER);
        when(self.isRadarVisible()).thenReturn(true);
        when(radarService.resolveSessionAvatarToUserId(SA_ID)).thenReturn(VIEWER);
        when(userRepository.findById(VIEWER)).thenReturn(Optional.of(self));
        when(dailyCodiRepository.findByUserId(VIEWER)).thenReturn(Optional.of(targetCodi));

        assertThatThrownBy(() -> service.likeCodi(VIEWER, SA_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        verify(codiLikeRepository, never()).save(any());
    }

    @Test
    void addComment_secondCommentWithinCooldown_isRejected() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Boolean.FALSE);  // 쿨다운 키가 이미 존재

        assertThatThrownBy(() -> service.addCodiComment(VIEWER, SA_ID, "도배!"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus())
                        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        verify(codiCommentRepository, never()).save(any());
    }

    @Test
    void addComment_saves_andResponseNeverContainsUserId() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Boolean.TRUE);
        when(codiCommentRepository.save(any(CodiComment.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        User viewer = mock(User.class);
        when(viewer.getNickname()).thenReturn("나");
        when(userRepository.findById(VIEWER)).thenReturn(Optional.of(viewer));

        Map<String, Object> dto = service.addCodiComment(VIEWER, SA_ID, "  옷 멋져요  ");

        assertThat(dto).containsEntry("content", "옷 멋져요")
                .containsEntry("nickname", "나")
                .containsEntry("is_mine", true);
        // 익명성 설계 — 작성자의 영속 ID는 어떤 형태로도 응답에 실리지 않는다
        assertThat(dto).doesNotContainKeys("user_id", "author_id");
        verify(userReportRepository, never()).save(any());
    }

    @Test
    void likeCodi_withoutCodi_is404() {
        when(dailyCodiRepository.findByUserId(TARGET)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.likeCodi(VIEWER, SA_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }
}
