package com.fsns.radar.avatar;

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
import com.fsns.radar.user.UserBlock;
import com.fsns.radar.user.UserBlockRepository;
import com.fsns.radar.user.UserReport;
import com.fsns.radar.user.UserReportRepository;
import com.fsns.radar.user.UserRepository;
import com.fsns.radar.wardrobe.ClothesItem;
import com.fsns.radar.wardrobe.ClothesItemRepository;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 설계서 2.2 ② 완성 — 타 유저와의 모든 상호작용(프로필 열람·차단·신고)은
 * 영속 user_id가 아닌 세션 스코프 session_avatar_id로만 이뤄진다.
 * saId → userId 해석은 이 서비스 내부(Redis)에서만 일어나고 응답에 새지 않는다.
 *
 * 노출/차단 규칙 위반 시 403이 아닌 404를 던진다 — "존재 여부" 자체가
 * 정보이므로, 고스트 모드 유저와 차단 관계 유저는 '없는 것'으로 취급한다.
 */
@Service
public class AvatarService {

    private final RadarService radarService;
    private final UserRepository userRepository;
    private final UserBlockRepository userBlockRepository;
    private final UserReportRepository userReportRepository;
    private final DailyCodiRepository dailyCodiRepository;
    private final DailyCodiItemRepository dailyCodiItemRepository;
    private final ClothesItemRepository clothesItemRepository;
    private final S3UrlSigner s3UrlSigner;
    private final CodiLikeRepository codiLikeRepository;
    private final CodiCommentRepository codiCommentRepository;
    private final StringRedisTemplate redis;

    public AvatarService(RadarService radarService,
                         UserRepository userRepository,
                         UserBlockRepository userBlockRepository,
                         UserReportRepository userReportRepository,
                         DailyCodiRepository dailyCodiRepository,
                         DailyCodiItemRepository dailyCodiItemRepository,
                         ClothesItemRepository clothesItemRepository,
                         S3UrlSigner s3UrlSigner,
                         CodiLikeRepository codiLikeRepository,
                         CodiCommentRepository codiCommentRepository,
                         StringRedisTemplate redis) {
        this.radarService = radarService;
        this.userRepository = userRepository;
        this.userBlockRepository = userBlockRepository;
        this.userReportRepository = userReportRepository;
        this.dailyCodiRepository = dailyCodiRepository;
        this.dailyCodiItemRepository = dailyCodiItemRepository;
        this.clothesItemRepository = clothesItemRepository;
        this.s3UrlSigner = s3UrlSigner;
        this.codiLikeRepository = codiLikeRepository;
        this.codiCommentRepository = codiCommentRepository;
        this.redis = redis;
    }

    public Map<String, Object> profile(Long viewerId, String sessionAvatarId) {
        User target = resolveVisibleTarget(viewerId, sessionAvatarId);
        Optional<DailyCodi> codi = dailyCodiRepository.findByUserId(target.getId());

        // 오늘의 코디 아이템 — 찜하기를 위해 item_id는 노출된다.
        // (아이템은 유저가 아닌 '물건'의 영속 ID이며 찜 테이블이 참조해야 함)
        List<Map<String, Object>> items = codi
                .map(c -> clothesItemRepository
                        .findAllById(dailyCodiItemRepository.findItemIds(c.getId()))
                        .stream().map(this::itemDto).toList())
                .orElse(List.of());

        Map<String, Object> profile = new HashMap<>();
        profile.put("session_avatar_id", sessionAvatarId);
        profile.put("nickname", target.getNickname());
        profile.put("avatar_url", target.getAvatarUrl());
        // 3D 아바타 GLB — 앱이 360° 뷰어로 렌더링 (설계서 4.2)
        profile.put("avatar_bundle_url", s3UrlSigner.signGet(target.getAvatarBundleKey()));
        profile.put("today_style_summary", clothesItemRepository
                .findFirstByUserIdOrderByCreatedAtDesc(target.getId())
                .map(ClothesItem::summary).orElse("스타일 정보 없음"));
        profile.put("codi_items", items);
        // 코디 반응 — 좋아요는 익명 카운트만 (누른 사람 목록은 어떤 API로도 노출 안 함)
        profile.put("codi_like_count",
                codi.map(c -> codiLikeRepository.countByIdCodiId(c.getId())).orElse(0L));
        profile.put("codi_liked_by_me",
                codi.map(c -> codiLikeRepository.existsById(
                        new CodiLike.Id(viewerId, c.getId()))).orElse(false));
        profile.put("codi_comment_count",
                codi.map(c -> codiCommentRepository.countByCodiId(c.getId())).orElse(0L));
        return profile;
    }

    /** 코디 좋아요 — 상대 착장에 대한 반응. 자기 자신에게는 불가. */
    public void likeCodi(Long viewerId, String sessionAvatarId) {
        DailyCodi codi = visibleCodi(viewerId, sessionAvatarId);
        if (codi.getUserId().equals(viewerId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "자신의 코디에는 좋아요를 누를 수 없습니다");
        }
        codiLikeRepository.save(new CodiLike(viewerId, codi.getId()));
    }

    public void unlikeCodi(Long viewerId, String sessionAvatarId) {
        DailyCodi codi = visibleCodi(viewerId, sessionAvatarId);
        codiLikeRepository.deleteById(new CodiLike.Id(viewerId, codi.getId()));
    }

    /** 코디 댓글 목록 — 차단 관계 작성자의 댓글은 숨긴다. 닉네임만 노출, 프로필 연결 없음. */
    public List<Map<String, Object>> codiComments(Long viewerId, String sessionAvatarId) {
        DailyCodi codi = visibleCodi(viewerId, sessionAvatarId);
        return commentDtos(viewerId, codi.getId(),
                userBlockRepository.findAllRelatedUserIds(viewerId));
    }

    public Map<String, Object> addCodiComment(Long viewerId, String sessionAvatarId,
                                              String content) {
        DailyCodi codi = visibleCodi(viewerId, sessionAvatarId);
        if (codi.getUserId().equals(viewerId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "자신의 코디에는 댓글을 쓸 수 없습니다");
        }
        // 도배 방지 — 유저당 10초 쿨다운 (Redis TTL)
        Boolean first = redis.opsForValue().setIfAbsent(
                "cooldown:comment:" + viewerId, "1", Duration.ofSeconds(10));
        if (Boolean.FALSE.equals(first)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "댓글은 10초에 한 번 쓸 수 있습니다");
        }
        CodiComment saved = codiCommentRepository.save(
                new CodiComment(codi.getId(), viewerId, content.trim()));
        return commentDto(saved, userRepository.findById(viewerId)
                .map(User::getNickname).orElse(""), true);
    }

    /** 코디 소유자용 — 내 코디에 달린 반응 (마이페이지). 차단 유저 댓글은 동일하게 숨김. */
    public Map<String, Object> myCodiReactions(Long ownerId, Long codiId) {
        Map<String, Object> reactions = new HashMap<>();
        reactions.put("like_count", codiLikeRepository.countByIdCodiId(codiId));
        reactions.put("comments", commentDtos(ownerId, codiId,
                userBlockRepository.findAllRelatedUserIds(ownerId)));
        return reactions;
    }

    private List<Map<String, Object>> commentDtos(Long viewerId, Long codiId,
                                                  Set<Long> excludedAuthors) {
        List<CodiComment> comments = codiCommentRepository.findAllByCodiIdOrderByIdAsc(codiId)
                .stream().filter(c -> !excludedAuthors.contains(c.getAuthorId())).toList();
        Map<Long, String> nicknames = userRepository
                .findAllById(comments.stream().map(CodiComment::getAuthorId).distinct().toList())
                .stream().collect(Collectors.toMap(User::getId, User::getNickname));
        return comments.stream()
                .map(c -> commentDto(c, nicknames.getOrDefault(c.getAuthorId(), ""),
                        c.getAuthorId().equals(viewerId)))
                .toList();
    }

    private static Map<String, Object> commentDto(CodiComment comment, String nickname,
                                                  boolean mine) {
        Map<String, Object> dto = new HashMap<>();
        dto.put("comment_id", comment.getId());
        dto.put("nickname", nickname);   // 작성자 식별은 닉네임 표시까지만 — saId/user_id 미노출
        dto.put("content", comment.getContent());
        dto.put("is_mine", mine);
        return dto;
    }

    private DailyCodi visibleCodi(Long viewerId, String sessionAvatarId) {
        User target = resolveVisibleTarget(viewerId, sessionAvatarId);
        return dailyCodiRepository.findByUserId(target.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "오늘의 코디가 없습니다"));
    }

    /** 차단 — 대상의 user_id는 서버 내부에서만 해석된다. */
    public void block(Long viewerId, String sessionAvatarId) {
        Long targetId = resolveUserId(sessionAvatarId);
        if (targetId.equals(viewerId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "자기 자신은 차단할 수 없습니다");
        }
        userBlockRepository.save(new UserBlock(viewerId, targetId));
    }

    public void report(Long viewerId, String sessionAvatarId, String reason) {
        Long targetId = resolveUserId(sessionAvatarId);
        if (targetId.equals(viewerId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "자기 자신은 신고할 수 없습니다");
        }
        userReportRepository.save(new UserReport(viewerId, targetId, reason));
    }

    private User resolveVisibleTarget(Long viewerId, String sessionAvatarId) {
        Long targetId = resolveUserId(sessionAvatarId);
        User target = userRepository.findById(targetId)
                .orElseThrow(AvatarService::notFound);
        Set<Long> excluded = userBlockRepository.findAllRelatedUserIds(viewerId);
        if (!target.isRadarVisible() || excluded.contains(targetId)) {
            throw notFound();
        }
        return target;
    }

    private Long resolveUserId(String sessionAvatarId) {
        Long userId = radarService.resolveSessionAvatarToUserId(sessionAvatarId);
        if (userId == null) {
            throw notFound();  // 세션 만료(TTL 2시간) 포함
        }
        return userId;
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "아바타를 찾을 수 없습니다");
    }

    private Map<String, Object> itemDto(ClothesItem item) {
        Map<String, Object> dto = new HashMap<>();
        dto.put("item_id", item.getId());
        dto.put("category", item.getCategory());
        dto.put("brand_info", item.getBrandInfo());
        dto.put("image_key", item.getImageKey());
        dto.put("product_url", item.getProductUrl());  // 찜 → "사러 가기" 연결
        Map<String, Object> meta = item.getMetaData();
        dto.put("photo_url", meta == null ? null
                : s3UrlSigner.signGet((String) meta.get("photo_key")));
        return dto;
    }
}
