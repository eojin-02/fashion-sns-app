package com.fsns.radar.user;

import com.fsns.radar.common.ApiException;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
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

    private final UserRepository userRepository;
    private final UserBlockRepository userBlockRepository;

    public UserController(UserRepository userRepository,
                          UserBlockRepository userBlockRepository) {
        this.userRepository = userRepository;
        this.userBlockRepository = userBlockRepository;
    }

    public record VisibilityRequest(@NotNull Boolean radar_visible) {}

    @GetMapping("/me")
    public Map<String, Object> me(Authentication auth) {
        User user = currentUser(auth);
        return Map.of(
                "id", user.getId(),
                "email", user.getEmail(),
                "nickname", user.getNickname(),
                "radar_visible", user.isRadarVisible());
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
