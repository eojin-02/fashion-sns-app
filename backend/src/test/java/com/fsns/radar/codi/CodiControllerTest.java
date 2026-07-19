package com.fsns.radar.codi;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fsns.radar.avatar.AvatarService;
import com.fsns.radar.common.ApiException;
import com.fsns.radar.feed.FeedPublisher;
import com.fsns.radar.radar.RadarService;
import com.fsns.radar.user.UserReport;
import com.fsns.radar.user.UserReportRepository;
import com.fsns.radar.wardrobe.ClothesItem;
import com.fsns.radar.wardrobe.ClothesItemRepository;
import com.fsns.radar.wardrobe.WardrobeService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.core.Authentication;

/**
 * 인프라 없이 순수 JVM에서 도는 단위 테스트.
 * 코디 저장이 3D 아바타 재생성으로 이어지는 연결 고리와
 * 타인 아이템 방어를 검증한다. GLB 생성 자체는 ai-server/test_avatar_builder.py 몫.
 */
class CodiControllerTest {

    private final DailyCodiRepository codiRepository = mock(DailyCodiRepository.class);
    private final DailyCodiItemRepository codiItemRepository = mock(DailyCodiItemRepository.class);
    private final ClothesItemRepository clothesItemRepository = mock(ClothesItemRepository.class);
    private final RadarService radarService = mock(RadarService.class);
    private final FeedPublisher feedPublisher = mock(FeedPublisher.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final WardrobeService wardrobeService = mock(WardrobeService.class);
    private final CodiLikeRepository codiLikeRepository = mock(CodiLikeRepository.class);
    private final CodiCommentRepository codiCommentRepository = mock(CodiCommentRepository.class);
    private final UserReportRepository userReportRepository = mock(UserReportRepository.class);
    private final AvatarService avatarService = mock(AvatarService.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);

    private final CodiController controller = new CodiController(
            codiRepository, codiItemRepository, clothesItemRepository,
            radarService, feedPublisher, redis, wardrobeService,
            codiLikeRepository, codiCommentRepository, userReportRepository, avatarService);

    private final Authentication auth = mock(Authentication.class);

    @BeforeEach
    void setUp() {
        when(auth.getPrincipal()).thenReturn(1L);
        when(redis.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void upsert_triggersAvatarRebuild() {
        ClothesItem mine = mock(ClothesItem.class);
        when(mine.getUserId()).thenReturn(1L);
        when(clothesItemRepository.findAllById(List.of(10L))).thenReturn(List.of(mine));
        DailyCodi codi = mock(DailyCodi.class);
        when(codi.getId()).thenReturn(3L);
        when(codiRepository.findByUserId(1L)).thenReturn(Optional.of(codi));

        controller.upsert(auth, new CodiController.CodiRequest(List.of(10L)));

        verify(wardrobeService).enqueueAvatarRebuild(1L);
        // 코디 교체 = 새 무대 — 이전 착장의 반응은 리셋되어야 한다
        verify(codiLikeRepository).deleteAllByIdCodiId(3L);
        verify(codiCommentRepository).deleteAllByCodiId(3L);
    }

    @Test
    void deleteComment_onlyAuthorCanDelete() {
        CodiComment mine = mock(CodiComment.class);
        when(mine.getAuthorId()).thenReturn(1L);
        when(codiCommentRepository.findById(7L)).thenReturn(Optional.of(mine));

        controller.deleteComment(auth, 7L);
        verify(codiCommentRepository).delete(mine);

        // 타인 댓글 — 존재 여부도 알리지 않는 404
        CodiComment others = mock(CodiComment.class);
        when(others.getAuthorId()).thenReturn(99L);
        when(codiCommentRepository.findById(8L)).thenReturn(Optional.of(others));
        assertThatThrownBy(() -> controller.deleteComment(auth, 8L))
                .isInstanceOf(ApiException.class);
        verify(codiCommentRepository, never()).delete(others);
    }

    @Test
    void reportComment_recordsAuthorWithSnippet_andRejectsSelfReport() {
        CodiComment others = mock(CodiComment.class);
        when(others.getAuthorId()).thenReturn(99L);
        when(others.getContent()).thenReturn("불쾌한 내용");
        when(codiCommentRepository.findById(7L)).thenReturn(Optional.of(others));

        controller.reportComment(auth, 7L);
        verify(userReportRepository).save(any(UserReport.class));

        CodiComment mine = mock(CodiComment.class);
        when(mine.getAuthorId()).thenReturn(1L);
        when(codiCommentRepository.findById(8L)).thenReturn(Optional.of(mine));
        assertThatThrownBy(() -> controller.reportComment(auth, 8L))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void upsert_withForeignItem_isRejected_andNoRebuild() {
        ClothesItem someoneElses = mock(ClothesItem.class);
        when(someoneElses.getUserId()).thenReturn(99L);
        when(clothesItemRepository.findAllById(List.of(10L)))
                .thenReturn(List.of(someoneElses));

        assertThatThrownBy(() ->
                controller.upsert(auth, new CodiController.CodiRequest(List.of(10L))))
                .isInstanceOf(ApiException.class);
        verify(wardrobeService, never()).enqueueAvatarRebuild(anyLong());
        verify(codiItemRepository, never()).save(any());
    }
}
