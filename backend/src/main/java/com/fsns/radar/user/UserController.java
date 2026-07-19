package com.fsns.radar.user;

import com.fsns.radar.common.ApiException;
import com.fsns.radar.common.S3UrlSigner;
import com.fsns.radar.wardrobe.WardrobeService;
import jakarta.validation.constraints.NotNull;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 계정 관리 API.
 * 타 유저에 대한 차단/신고는 여기가 아닌 AvatarController(session_avatar_id 기반)에
 * 있다 — 클라이언트가 타인의 user_id를 다루지 않게 하기 위함 (설계서 2.2).
 * 유일한 예외는 차단 목록/해제: 이미 차단한 상대는 세션이 끝나도 관리할 수 있어야
 * 하므로 여기서만 user_id를 노출한다.
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    /** 아바타 베이스 파라미터 허용값 — ai-server/avatar_builder.py의 팔레트와 동기 유지.
     *  "auto" = 스캔 사진에서 감지한 헤어(detected_*)를 따라간다 (기본값). */
    private static final Map<String, Set<String>> AVATAR_CONFIG_ALLOWED = Map.of(
            "skin", Set.of("light", "tan", "deep"),
            "hair_color", Set.of("auto", "black", "brown", "blonde", "red", "blue", "pink"),
            "hair_style", Set.of("auto", "short", "long", "bald"));

    private final UserRepository userRepository;
    private final UserBlockRepository userBlockRepository;
    private final S3UrlSigner s3UrlSigner;
    private final WardrobeService wardrobeService;

    public UserController(UserRepository userRepository,
                          UserBlockRepository userBlockRepository,
                          S3UrlSigner s3UrlSigner,
                          WardrobeService wardrobeService) {
        this.userRepository = userRepository;
        this.userBlockRepository = userBlockRepository;
        this.s3UrlSigner = s3UrlSigner;
        this.wardrobeService = wardrobeService;
    }

    public record VisibilityRequest(@NotNull Boolean radar_visible) {}

    @GetMapping("/me")
    public Map<String, Object> me(Authentication auth) {
        User user = currentUser(auth);
        Map<String, Object> me = new HashMap<>();
        me.put("id", user.getId());
        me.put("email", user.getEmail());
        me.put("nickname", user.getNickname());
        me.put("radar_visible", user.isRadarVisible());
        // 내 3D 아바타 GLB — 옷 등록 전이면 null (설계서 4.2)
        me.put("avatar_bundle_url", s3UrlSigner.signGet(user.getAvatarBundleKey()));
        me.put("avatar_config", user.getAvatarConfig());
        return me;
    }

    /**
     * 아바타 베이스 파라미터(피부/헤어) 변경 — 가입 시 1회 생성 후 언제든 수정 가능.
     * 저장 즉시 재생성 잡을 큐에 넣어 현재 착장을 유지한 채 몸만 바뀐다.
     */
    @PatchMapping("/me/avatar-config")
    @Transactional
    public Map<String, Object> setAvatarConfig(Authentication auth,
                                               @RequestBody Map<String, String> req) {
        for (Map.Entry<String, String> entry : req.entrySet()) {
            Set<String> allowed = AVATAR_CONFIG_ALLOWED.get(entry.getKey());
            if (allowed == null || entry.getValue() == null || !allowed.contains(entry.getValue())) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "지원하지 않는 아바타 설정입니다: " + entry.getKey());
            }
        }
        User user = currentUser(auth);
        // 교체가 아닌 병합 — 워커가 기록한 사진 감지값(detected_*)을 지우면 안 된다
        Map<String, Object> config = user.getAvatarConfig() == null
                ? new HashMap<>() : new HashMap<>(user.getAvatarConfig());
        config.putAll(req);
        user.setAvatarConfig(config);
        userRepository.save(user);
        wardrobeService.enqueueAvatarRebuild(user.getId());
        return Map.of("avatar_config", user.getAvatarConfig());
    }

    /** 고스트 모드 토글 — 언제든 레이더 비노출 선택 가능 (설계서 1.2) */
    @PatchMapping("/me/visibility")
    @Transactional
    public Map<String, Object> setVisibility(Authentication auth, @RequestBody VisibilityRequest req) {
        User user = currentUser(auth);
        user.setRadarVisible(req.radar_visible());
        userRepository.save(user);
        return Map.of("radar_visible", user.isRadarVisible());
    }

    /** 차단 목록 (해제 관리용 — user_id 노출의 유일한 예외 지점) */
    @GetMapping("/me/blocks")
    public List<Map<String, Object>> myBlocks(Authentication auth) {
        Long me = (Long) auth.getPrincipal();
        List<Long> blockedIds = userBlockRepository.findBlockedIdsByBlocker(me);
        return userRepository.findAllById(blockedIds).stream()
                .map(u -> Map.<String, Object>of(
                        "user_id", u.getId(),
                        "nickname", u.getNickname()))
                .toList();
    }

    @DeleteMapping("/me/blocks/{targetId}")
    public ResponseEntity<Void> unblock(Authentication auth, @PathVariable Long targetId) {
        Long me = (Long) auth.getPrincipal();
        userBlockRepository.deleteById(new UserBlock.Id(me, targetId));
        return ResponseEntity.noContent().build();
    }

    private User currentUser(Authentication auth) {
        return userRepository.findById((Long) auth.getPrincipal())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "유저를 찾을 수 없습니다"));
    }
}
