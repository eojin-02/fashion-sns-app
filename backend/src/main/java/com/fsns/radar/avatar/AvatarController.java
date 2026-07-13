package com.fsns.radar.avatar;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 세션 아바타 기반 상호작용 API.
 * 갤러리/레이더에서 받은 session_avatar_id 하나로 프로필 열람·차단·신고가
 * 전부 가능하므로, 클라이언트는 타인의 user_id를 알 필요가 전혀 없다.
 */
@RestController
@RequestMapping("/api/v1/avatars")
public class AvatarController {

    private final AvatarService avatarService;

    public AvatarController(AvatarService avatarService) {
        this.avatarService = avatarService;
    }

    public record ReportRequest(String reason) {}

    @GetMapping("/{saId}")
    public Map<String, Object> profile(Authentication auth, @PathVariable String saId) {
        return avatarService.profile((Long) auth.getPrincipal(), saId);
    }

    @PostMapping("/{saId}/block")
    public ResponseEntity<Void> block(Authentication auth, @PathVariable String saId) {
        avatarService.block((Long) auth.getPrincipal(), saId);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/{saId}/report")
    public ResponseEntity<Void> report(Authentication auth, @PathVariable String saId,
                                       @RequestBody(required = false) ReportRequest req) {
        avatarService.report((Long) auth.getPrincipal(), saId,
                req == null ? null : req.reason());
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }
}
