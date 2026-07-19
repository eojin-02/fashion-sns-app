package com.fsns.radar.codi;

import com.fsns.radar.avatar.AvatarService;
import com.fsns.radar.common.ApiException;
import com.fsns.radar.feed.FeedPublisher;
import com.fsns.radar.radar.RadarService;
import com.fsns.radar.user.UserReport;
import com.fsns.radar.user.UserReportRepository;
import com.fsns.radar.wardrobe.ClothesItemRepository;
import com.fsns.radar.wardrobe.WardrobeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/codi")
public class CodiController {

    private final DailyCodiRepository codiRepository;
    private final DailyCodiItemRepository codiItemRepository;
    private final ClothesItemRepository clothesItemRepository;
    private final RadarService radarService;
    private final FeedPublisher feedPublisher;
    private final StringRedisTemplate redis;
    private final WardrobeService wardrobeService;
    private final CodiLikeRepository codiLikeRepository;
    private final CodiCommentRepository codiCommentRepository;
    private final UserReportRepository userReportRepository;
    private final AvatarService avatarService;

    public CodiController(DailyCodiRepository codiRepository,
                          DailyCodiItemRepository codiItemRepository,
                          ClothesItemRepository clothesItemRepository,
                          RadarService radarService,
                          FeedPublisher feedPublisher,
                          StringRedisTemplate redis,
                          WardrobeService wardrobeService,
                          CodiLikeRepository codiLikeRepository,
                          CodiCommentRepository codiCommentRepository,
                          UserReportRepository userReportRepository,
                          AvatarService avatarService) {
        this.codiRepository = codiRepository;
        this.codiItemRepository = codiItemRepository;
        this.clothesItemRepository = clothesItemRepository;
        this.radarService = radarService;
        this.feedPublisher = feedPublisher;
        this.redis = redis;
        this.wardrobeService = wardrobeService;
        this.codiLikeRepository = codiLikeRepository;
        this.codiCommentRepository = codiCommentRepository;
        this.userReportRepository = userReportRepository;
        this.avatarService = avatarService;
    }

    public record CodiRequest(@NotEmpty List<Long> item_ids) {}

    /** 오늘의 코디 설정 (유저당 1개, 전체 교체 방식) */
    @PutMapping
    @Transactional
    public Map<String, Object> upsert(Authentication auth, @Valid @RequestBody CodiRequest req) {
        Long userId = (Long) auth.getPrincipal();

        boolean allMine = clothesItemRepository.findAllById(req.item_ids()).stream()
                .filter(i -> i.getUserId().equals(userId))
                .count() == req.item_ids().stream().distinct().count();
        if (!allMine) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "본인 옷장의 아이템만 코디에 담을 수 있습니다");
        }

        DailyCodi codi = codiRepository.findByUserId(userId)
                .orElseGet(() -> codiRepository.save(new DailyCodi(userId)));
        codi.touch();
        codiRepository.save(codi);

        codiItemRepository.deleteAllByCodiId(codi.getId());
        req.item_ids().stream().distinct()
                .forEach(itemId -> codiItemRepository.save(new DailyCodiItem(codi.getId(), itemId)));

        // 코디 교체 = 새 무대 — 이전 착장에 대한 좋아요/댓글은 리셋 (스토리형 휘발성)
        codiLikeRepository.deleteAllByIdCodiId(codi.getId());
        codiCommentRepository.deleteAllByCodiId(codi.getId());

        // 3D 아바타 재생성 — 워커가 코디 아이템 태그로 GLB를 다시 만든다 (사진 재분석 없음)
        wardrobeService.enqueueAvatarRebuild(userId);

        // 같은 방 유저에게 실시간 피드 push — 영속 ID가 아닌 세션 아바타 ID로 (설계서 2.2)
        String dongCode = redis.opsForValue().get("lastroom:dong:" + userId);
        if (dongCode != null) {
            feedPublisher.publish(dongCode, Map.of(
                    "type", "CODI_UPDATED",
                    "session_avatar_id", radarService.sessionAvatarId(userId, dongCode)));
        }

        return Map.of("codi_id", codi.getId(), "item_ids", req.item_ids());
    }

    @GetMapping
    public Map<String, Object> mine(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        DailyCodi codi = codiRepository.findByUserId(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "오늘의 코디가 없습니다"));
        Map<String, Object> response = new HashMap<>();
        response.put("codi_id", codi.getId());
        response.put("item_ids", codiItemRepository.findItemIds(codi.getId()));
        response.put("updated_at", codi.getUpdatedAt().toString());
        // 내 코디가 받은 반응 (마이페이지) — like_count + comments
        response.putAll(avatarService.myCodiReactions(userId, codi.getId()));
        return response;
    }

    /** 본인이 쓴 댓글 삭제 — 작성자 이외에는 존재 여부도 알리지 않는다(404) */
    @DeleteMapping("/comments/{commentId}")
    public ResponseEntity<Void> deleteComment(Authentication auth,
                                              @PathVariable Long commentId) {
        Long me = (Long) auth.getPrincipal();
        CodiComment comment = codiCommentRepository.findById(commentId)
                .filter(c -> c.getAuthorId().equals(me))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "댓글을 찾을 수 없습니다"));
        codiCommentRepository.delete(comment);
        return ResponseEntity.noContent().build();
    }

    /** 댓글 신고 — 기존 유저 신고 테이블에 내용 스니펫과 함께 적재 (UGC 정책 대응) */
    @PostMapping("/comments/{commentId}/report")
    public ResponseEntity<Void> reportComment(Authentication auth,
                                              @PathVariable Long commentId) {
        Long me = (Long) auth.getPrincipal();
        CodiComment comment = codiCommentRepository.findById(commentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "댓글을 찾을 수 없습니다"));
        if (comment.getAuthorId().equals(me)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "자신의 댓글은 신고할 수 없습니다");
        }
        userReportRepository.save(new UserReport(me, comment.getAuthorId(),
                "코디 댓글 신고: " + comment.getContent()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }
}
