package com.fsns.radar.user;

import com.fsns.radar.common.ApiException;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserRepository userRepository;
    private final UserBlockRepository userBlockRepository;
    private final UserReportRepository userReportRepository;

    public UserController(UserRepository userRepository,
                          UserBlockRepository userBlockRepository,
                          UserReportRepository userReportRepository) {
        this.userRepository = userRepository;
        this.userBlockRepository = userBlockRepository;
        this.userReportRepository = userReportRepository;
    }

    public record VisibilityRequest(@NotNull Boolean radar_visible) {}
    public record ReportRequest(String reason) {}

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

    @PostMapping("/{targetId}/block")
    public ResponseEntity<Void> block(Authentication auth, @PathVariable Long targetId) {
        Long me = (Long) auth.getPrincipal();
        if (me.equals(targetId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "자기 자신은 차단할 수 없습니다");
        }
        if (!userRepository.existsById(targetId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "존재하지 않는 유저입니다");
        }
        userBlockRepository.save(new UserBlock(me, targetId));
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @DeleteMapping("/{targetId}/block")
    public ResponseEntity<Void> unblock(Authentication auth, @PathVariable Long targetId) {
        Long me = (Long) auth.getPrincipal();
        userBlockRepository.deleteById(new UserBlock.Id(me, targetId));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{targetId}/report")
    public ResponseEntity<Void> report(Authentication auth, @PathVariable Long targetId,
                                       @RequestBody(required = false) ReportRequest req) {
        Long me = (Long) auth.getPrincipal();
        if (!userRepository.existsById(targetId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "존재하지 않는 유저입니다");
        }
        userReportRepository.save(new UserReport(me, targetId, req == null ? null : req.reason()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    private User currentUser(Authentication auth) {
        return userRepository.findById((Long) auth.getPrincipal())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "유저를 찾을 수 없습니다"));
    }
}
