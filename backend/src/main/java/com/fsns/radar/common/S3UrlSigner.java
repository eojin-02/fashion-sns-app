package com.fsns.radar.common;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * S3 오브젝트 조회용 Presigned GET URL 서명자.
 * 버킷을 public으로 열지 않고도 앱이 아바타 GLB 등 미디어를 직접 내려받게 한다
 * (업로드와 동일하게 미디어 바이트는 Spring 서버를 경유하지 않는다 — 설계서 2.1).
 */
@Service
public class S3UrlSigner {

    private final S3Presigner presigner;
    private final String bucket;
    private final Duration ttl;

    public S3UrlSigner(S3Presigner presigner,
                       @Value("${app.s3.bucket}") String bucket,
                       @Value("${app.s3.presign-ttl-seconds}") long ttlSeconds) {
        this.presigner = presigner;
        this.bucket = bucket;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    /** @return presigned GET URL, key가 null/빈 값이면 null */
    public String signGet(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return presigner.presignGetObject(GetObjectPresignRequest.builder()
                        .getObjectRequest(GetObjectRequest.builder()
                                .bucket(bucket).key(key).build())
                        .signatureDuration(ttl)
                        .build())
                .url().toString();
    }
}
