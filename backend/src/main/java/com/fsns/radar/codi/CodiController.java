package com.fsns.radar.codi;

import com.fsns.radar.common.ApiException;
import com.fsns.radar.feed.FeedPublisher;
import com.fsns.radar.radar.RadarService;
import com.fsns.radar.wardrobe.ClothesItemRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
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

    public CodiController(DailyCodiRepository codiRepository,
                          DailyCodiItemRepository codiItemRepository,
                          ClothesItemRepository clothesItemRepository,
                          RadarService radarService,
                          FeedPublisher feedPublisher,
                          StringRedisTemplate redis) {
        this.codiRepository = codiRepository;
        this.codiItemRepository = codiItemRepository;
        this.clothesItemRepository = clothesItemRepository;
        this.radarService = radarService;
        this.feedPublisher = feedPublisher;
        this.redis = redis;
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
        return Map.of(
                "codi_id", codi.getId(),
                "item_ids", codiItemRepository.findItemIds(codi.getId()),
                "updated_at", codi.getUpdatedAt().toString());
    }
}
