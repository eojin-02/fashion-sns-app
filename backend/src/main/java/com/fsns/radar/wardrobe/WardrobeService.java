package com.fsns.radar.wardrobe;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fsns.radar.common.ApiException;
import java.time.Duration;
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

    public ClothesItem enqueueScan(Long userId, String imageKey) {
        if (!imageKey.startsWith("u" + userId + "/")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "본인이 업로드한 이미지만 등록할 수 있습니다");
        }
        enforceDailyLimit(userId);

        ClothesItem item = clothesItemRepository.save(new ClothesItem(userId, imageKey));
        try {
            String job = objectMapper.writeValueAsString(Map.of(
                    "item_id", item.getId(),
                    "user_id", userId,
                    "image_key", imageKey));
            redis.opsForList().leftPush(SCAN_QUEUE, job);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return item;
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
