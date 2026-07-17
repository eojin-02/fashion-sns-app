package com.fsns.radar.wardrobe;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fsns.radar.common.ApiException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * 설계서 2.6 — AI 파이프라인은 비동기 큐.
 * 이미지는 Presigned URL로 S3에 직접 올라가고(서버 미경유),
 * scan 요청은 큐 적재 후 202를 즉시 반환한다. 실제 분석은 FastAPI 워커 몫.
 */
@Service
public class WardrobeService {

    public static final String SCAN_QUEUE = "queue:scan";

    private final S3Presigner presigner;
    private final StringRedisTemplate redis;
    private final ClothesItemRepository clothesItemRepository;
    private final ObjectMapper objectMapper;
    private final String bucket;
    private final Duration presignTtl;
    private final int dailyScanLimit;

    public WardrobeService(S3Presigner presigner,
                           StringRedisTemplate redis,
                           ClothesItemRepository clothesItemRepository,
                           ObjectMapper objectMapper,
                           @Value("${app.s3.bucket}") String bucket,
                           @Value("${app.s3.presign-ttl-seconds}") long presignTtlSeconds,
                           @Value("${app.scan.daily-limit}") int dailyScanLimit) {
        this.presigner = presigner;
        this.redis = redis;
        this.clothesItemRepository = clothesItemRepository;
        this.objectMapper = objectMapper;
        this.bucket = bucket;
        this.presignTtl = Duration.ofSeconds(presignTtlSeconds);
        this.dailyScanLimit = dailyScanLimit;
    }

    public record UploadUrl(String presigned_url, String image_key) {}

    public UploadUrl createUploadUrl(Long userId) {
        String imageKey = "u" + userId + "/" + UUID.randomUUID() + ".jpg";
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(bucket)
                .key(imageKey)
                .contentType("image/jpeg")
                .build();
        String url = presigner.presignPutObject(PutObjectPresignRequest.builder()
                        .putObjectRequest(put)
                        .signatureDuration(presignTtl)
                        .build())
                .url().toString();
        return new UploadUrl(url, imageKey);
    }

    public ClothesItem enqueueScan(Long userId, String imageKey, String productUrl) {
        if (!imageKey.startsWith("u" + userId + "/")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "본인이 업로드한 이미지만 등록할 수 있습니다");
        }
        String normalizedUrl = validateProductUrl(productUrl);
        enforceDailyLimit(userId);

        ClothesItem item = clothesItemRepository.save(
                new ClothesItem(userId, imageKey, normalizedUrl));
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("item_id", item.getId());
            payload.put("user_id", userId);
            payload.put("image_key", imageKey);
            if (normalizedUrl != null) {
                payload.put("product_url", normalizedUrl);
            }
            redis.opsForList().leftPush(SCAN_QUEUE, objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return item;
    }

    /**
     * 상품 URL 형식 검증 (선택 입력 — 빈 값은 null).
     * 사설망 차단 등 SSRF 방어는 실제로 URL을 가져가는 워커(product_info.py) 몫이고,
     * 여기서는 명백한 쓰레기 값만 조기에 거른다.
     */
    private String validateProductUrl(String productUrl) {
        if (productUrl == null || productUrl.isBlank()) {
            return null;
        }
        String trimmed = productUrl.trim();
        if (trimmed.length() > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "상품 URL이 너무 깁니다 (최대 500자)");
        }
        boolean http = trimmed.startsWith("http://") || trimmed.startsWith("https://");
        if (!http) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "상품 URL은 http/https만 허용됩니다");
        }
        try {
            java.net.URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "올바른 URL 형식이 아닙니다");
        }
        return trimmed;
    }

    /**
     * 3D 아바타 재생성 잡 적재 — 코디 변경·아바타 설정 변경·가입 시 호출된다.
     * 스캔과 같은 큐를 쓰되 type으로 구분해 워커 소비 루프를 하나로 유지한다.
     */
    public void enqueueAvatarRebuild(Long userId) {
        try {
            String job = objectMapper.writeValueAsString(Map.of(
                    "type", "AVATAR_ONLY",
                    "user_id", userId));
            redis.opsForList().leftPush(SCAN_QUEUE, job);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 비용 통제: 유저당 일일 스캔 횟수 제한 — Redis 카운터 (설계서 2.6) */
    private void enforceDailyLimit(Long userId) {
        String key = "ratelimit:scan:" + userId + ":daily";
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1) {
            redis.expire(key, Duration.ofHours(24));
        }
        if (count != null && count > dailyScanLimit) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "일일 스캔 한도(" + dailyScanLimit + "회)를 초과했습니다");
        }
    }
}
