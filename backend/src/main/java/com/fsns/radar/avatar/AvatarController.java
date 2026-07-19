package com.fsns.radar.avatar;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
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

    public record CommentRequest(
            @NotBlank(message = "댓글 내용을 입력해주세요")
            @Size(max = 200, message = "댓글은 200자까지 쓸 수 있습니다")
            String content) {}

    @GetMapping("/{saId}")
    public Map<String, Object> profile(Authentication auth, @PathVariable String saId) {
        return avatarService.profile((Long) auth.getPrincipal(), saId);
    }

    /** 코디 좋아요 — 응답에 카운트만 있고 누른 사람 목록은 존재하지 않는다 */
    @PostMapping("/{saId}/codi/like")
    public ResponseEntity<Void> likeCodi(Authentication auth, @PathVariable String saId) {
        avatarService.likeCodi((Long) auth.getPrincipal(), saId);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @DeleteMapping("/{saId}/codi/like")
    public ResponseEntity<Void> unlikeCodi(Authentication auth, @PathVariable String saId) {
        avatarService.unlikeCodi((Long) auth.getPrincipal(), saId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{saId}/codi/comments")
    public List<Map<String, Object>> codiComments(Authentication auth,
                                                  @PathVariable String saId) {
        return avatarService.codiComments((Long) auth.getPrincipal(), saId);
    }

    @PostMapping("/{saId}/codi/comments")
    public ResponseEntity<Map<String, Object>> addCodiComment(
            Authentication auth, @PathVariable String saId,
            @Valid @RequestBody CommentRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                avatarService.addCodiComment((Long) auth.getPrincipal(), saId, req.content()));
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
