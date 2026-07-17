package com.fsns.radar.avatar;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    public AvatarService(RadarService radarService,
                         UserRepository userRepository,
                         UserBlockRepository userBlockRepository,
                         UserReportRepository userReportRepository,
                         DailyCodiRepository dailyCodiRepository,
                         DailyCodiItemRepository dailyCodiItemRepository,
                         ClothesItemRepository clothesItemRepository,
                         S3UrlSigner s3UrlSigner) {
        this.radarService = radarService;
        this.userRepository = userRepository;
        this.userBlockRepository = userBlockRepository;
        this.userReportRepository = userReportRepository;
        this.dailyCodiRepository = dailyCodiRepository;
        this.dailyCodiItemRepository = dailyCodiItemRepository;
        this.clothesItemRepository = clothesItemRepository;
        this.s3UrlSigner = s3UrlSigner;
    }

    public Map<String, Object> profile(Long viewerId, String sessionAvatarId) {
        User target = resolveVisibleTarget(viewerId, sessionAvatarId);

        // 오늘의 코디 아이템 — 찜하기를 위해 item_id는 노출된다.
        // (아이템은 유저가 아닌 '물건'의 영속 ID이며 찜 테이블이 참조해야 함)
        List<Map<String, Object>> items = dailyCodiRepository.findByUserId(target.getId())
                .map(codi -> clothesItemRepository
                        .findAllById(dailyCodiItemRepository.findItemIds(codi.getId()))
                        .stream().map(AvatarService::itemDto).toList())
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
        return profile;
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

    private static Map<String, Object> itemDto(ClothesItem item) {
        Map<String, Object> dto = new HashMap<>();
        dto.put("item_id", item.getId());
        dto.put("category", item.getCategory());
        dto.put("brand_info", item.getBrandInfo());
        dto.put("image_key", item.getImageKey());
        dto.put("product_url", item.getProductUrl());  // 찜 → "사러 가기" 연결
        return dto;
    }
}
