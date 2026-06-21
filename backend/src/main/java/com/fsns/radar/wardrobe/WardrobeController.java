package com.fsns.radar.wardrobe;

import com.fsns.radar.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.HashMap;
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

@RestController
@RequestMapping("/api/v1/wardrobe")
public class WardrobeController {

    private final WardrobeService wardrobeService;
    private final ClothesItemRepository clothesItemRepository;
    private final ItemLikeRepository itemLikeRepository;

    public WardrobeController(WardrobeService wardrobeService,
                              ClothesItemRepository clothesItemRepository,
                              ItemLikeRepository itemLikeRepository) {
        this.wardrobeService = wardrobeService;
        this.clothesItemRepository = clothesItemRepository;
        this.itemLikeRepository = itemLikeRepository;
    }

    public record ScanRequest(@NotBlank String image_key) {}

    /** 설계서 4.1 ① — Presigned URL 발급. 이미지는 서버를 경유하지 않는다. */
    @PostMapping("/upload-url")
    public WardrobeService.UploadUrl uploadUrl(Authentication auth) {
        return wardrobeService.createUploadUrl((Long) auth.getPrincipal());
    }

    /** 설계서 4.1 ③ — 큐 적재 후 202 즉시 반환. 완료는 WebSocket/푸시로 통지. */
    @PostMapping("/scan")
    public ResponseEntity<Map<String, Object>> scan(Authentication auth,
                                                    @Valid @RequestBody ScanRequest req) {
        ClothesItem item = wardrobeService.enqueueScan((Long) auth.getPrincipal(), req.image_key());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "job_id", "scan-" + item.getId(),
                "item_id", item.getId(),
                "scan_status", item.getScanStatus()));
    }

    @GetMapping("/items")
    public List<Map<String, Object>> myItems(Authentication auth) {
        return clothesItemRepository.findAllByUserIdOrderByCreatedAtDesc((Long) auth.getPrincipal())
                .stream().map(WardrobeController::toDto).toList();
    }

    @GetMapping("/items/{itemId}")
    public Map<String, Object> item(@PathVariable Long itemId) {
        ClothesItem item = clothesItemRepository.findById(itemId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "아이템을 찾을 수 없습니다"));
        return toDto(item);
    }

    /** 찜하기 (설계서 1.2 MVP) */
    @PostMapping("/items/{itemId}/like")
    public ResponseEntity<Void> like(Authentication auth, @PathVariable Long itemId) {
        if (!clothesItemRepository.existsById(itemId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "아이템을 찾을 수 없습니다");
        }
        itemLikeRepository.save(new ItemLike((Long) auth.getPrincipal(), itemId));
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @DeleteMapping("/items/{itemId}/like")
    public ResponseEntity<Void> unlike(Authentication auth, @PathVariable Long itemId) {
        itemLikeRepository.deleteById(new ItemLike.Id((Long) auth.getPrincipal(), itemId));
        return ResponseEntity.noContent().build();
    }

    /** 마이페이지 찜 모아보기 */
    @GetMapping("/likes")
    public List<Map<String, Object>> likes(Authentication auth) {
        return itemLikeRepository.findLikedItems((Long) auth.getPrincipal())
                .stream().map(WardrobeController::toDto).toList();
    }

    private static Map<String, Object> toDto(ClothesItem item) {
        Map<String, Object> dto = new HashMap<>();
        dto.put("id", item.getId());
        dto.put("category", item.getCategory());
        dto.put("brand_info", item.getBrandInfo());
        dto.put("meta_data", item.getMetaData());
        dto.put("image_key", item.getImageKey());
        dto.put("scan_status", item.getScanStatus());
        return dto;
    }
}
