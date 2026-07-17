package com.fsns.radar.location;

import com.fsns.radar.common.ApiException;
import com.fsns.radar.common.S3UrlSigner;
import com.fsns.radar.feed.FeedPublisher;
import com.fsns.radar.radar.RadarService;
import com.fsns.radar.user.User;
import com.fsns.radar.user.UserBlockRepository;
import com.fsns.radar.user.UserRepository;
import com.fsns.radar.wardrobe.ClothesItem;
import com.fsns.radar.wardrobe.ClothesItemRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 설계서 2.5 — 행정구역 방 관리.
 * PostGIS ST_Contains로 동(洞) 판정. 경계 핑퐁 방지를 위해 최소 체류 시간을 적용한다.
 * (50m 경계 버퍼는 OS 지오펜스 등록 시 클라이언트가 적용 — Flutter geofence_service 참고)
 */
@Service
public class RoomService {

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final RadarService radarService;
    private final UserRepository userRepository;
    private final UserBlockRepository userBlockRepository;
    private final ClothesItemRepository clothesItemRepository;
    private final FeedPublisher feedPublisher;
    private final S3UrlSigner s3UrlSigner;
    private final long presenceWindowSeconds;
    private final long roomDwellSeconds;

    public RoomService(JdbcTemplate jdbc,
                       StringRedisTemplate redis,
                       RadarService radarService,
                       UserRepository userRepository,
                       UserBlockRepository userBlockRepository,
                       ClothesItemRepository clothesItemRepository,
                       FeedPublisher feedPublisher,
                       S3UrlSigner s3UrlSigner,
                       @Value("${app.radar.presence-window-seconds}") long presenceWindowSeconds,
                       @Value("${app.radar.room-dwell-seconds}") long roomDwellSeconds) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.radarService = radarService;
        this.userRepository = userRepository;
        this.userBlockRepository = userBlockRepository;
        this.clothesItemRepository = clothesItemRepository;
        this.feedPublisher = feedPublisher;
        this.s3UrlSigner = s3UrlSigner;
        this.presenceWindowSeconds = presenceWindowSeconds;
        this.roomDwellSeconds = roomDwellSeconds;
    }

    public record Dong(String code, String name) {}

    public Map<String, Object> enter(Long userId, double latitude, double longitude) {
        Dong dong = locate(latitude, longitude);
        if (dong == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "서비스 지역이 아닙니다");
        }
        dong = applyDwellGuard(userId, dong);

        // Tier 2 Presence: Sorted Set(최근 활성 시각) — 설계서 3.2 Redis 데이터
        long now = Instant.now().getEpochSecond();
        String activeKey = "room:" + dong.code() + ":active";
        redis.opsForZSet().add(activeKey, String.valueOf(userId), now);
        redis.opsForZSet().removeRangeByScore(activeKey, 0, now - presenceWindowSeconds);

        var issued = radarService.issueToken(userId, dong.code());
        List<Map<String, Object>> gallery = buildGallery(userId, dong.code(), activeKey, now);

        feedPublisher.publish(dong.code(), Map.of(
                "type", "ENTER",
                "session_avatar_id", radarService.sessionAvatarId(userId, dong.code())));

        Map<String, Object> response = new HashMap<>();
        response.put("room_code", dong.code());
        response.put("room_name", dong.name());
        response.put("my_ble_token", issued.token());          // 내 토큰만. 타인 토큰 목록 없음 (설계서 2.2)
        response.put("token_expires_in_sec", issued.expiresInSeconds());
        response.put("gallery", gallery);
        return response;
    }

    private Dong locate(double latitude, double longitude) {
        List<Dong> rows = jdbc.query("""
                SELECT dong_code, name FROM admin_dong
                WHERE ST_Contains(geom, ST_SetSRID(ST_MakePoint(?, ?), 4326))
                LIMIT 1
                """,
                (rs, i) -> new Dong(rs.getString("dong_code"), rs.getString("name")),
                longitude, latitude);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 방 핑퐁 방지: 직전 방 진입 후 최소 체류 시간이 지나기 전엔
     * 다른 방으로의 전환을 무시한다 (성수1가 ↔ 성수2가 왕복 방지).
     */
    private Dong applyDwellGuard(Long userId, Dong newDong) {
        long now = Instant.now().getEpochSecond();
        String prevDong = redis.opsForValue().get("lastroom:dong:" + userId);
        String sinceStr = redis.opsForValue().get("lastroom:since:" + userId);
        long since = sinceStr == null ? 0 : Long.parseLong(sinceStr);

        if (prevDong != null && !prevDong.equals(newDong.code()) && now - since < roomDwellSeconds) {
            String prevName = jdbc.queryForObject(
                    "SELECT name FROM admin_dong WHERE dong_code = ?", String.class, prevDong);
            return new Dong(prevDong, prevName);
        }
        if (prevDong == null || !prevDong.equals(newDong.code())) {
            redis.opsForValue().set("lastroom:dong:" + userId, newDong.code());
            redis.opsForValue().set("lastroom:since:" + userId, String.valueOf(now));
        }
        return newDong;
    }

    /** Tier 2 갤러리: 같은 방 + 최근 활성. BLE 강조 없음(highlight는 resolve에서만). */
    private List<Map<String, Object>> buildGallery(Long userId, String dongCode,
                                                   String activeKey, long now) {
        Set<String> members = redis.opsForZSet()
                .rangeByScore(activeKey, now - presenceWindowSeconds, Double.MAX_VALUE);
        Set<Long> excluded = userBlockRepository.findAllRelatedUserIds(userId);

        List<Map<String, Object>> gallery = new ArrayList<>();
        if (members == null) {
            return gallery;
        }
        for (String member : members) {
            long otherId = Long.parseLong(member);
            if (otherId == userId || excluded.contains(otherId)) {
                continue;
            }
            User other = userRepository.findById(otherId).orElse(null);
            if (other == null || !other.isRadarVisible()) {
                continue;  // 고스트 모드: opt-in 하지 않은 유저는 갤러리에도 미노출
            }
            Map<String, Object> card = new HashMap<>();
            card.put("session_avatar_id", radarService.sessionAvatarId(otherId, dongCode));
            card.put("avatar_url", other.getAvatarUrl());
            // 3D 아바타 GLB (설계서 4.2) — 아직 옷을 등록하지 않은 유저는 null
            card.put("avatar_bundle_url", s3UrlSigner.signGet(other.getAvatarBundleKey()));
            card.put("today_style_summary", styleSummary(otherId));
            gallery.add(card);
        }
        return gallery;
    }

    private String styleSummary(Long userId) {
        return clothesItemRepository.findFirstByUserIdOrderByCreatedAtDesc(userId)
                .map(ClothesItem::summary)
                .orElse("스타일 정보 없음");
    }
}
