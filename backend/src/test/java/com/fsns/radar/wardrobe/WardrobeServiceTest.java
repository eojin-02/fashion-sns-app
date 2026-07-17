package com.fsns.radar.wardrobe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * 인프라 없이 순수 JVM에서 도는 단위 테스트.
 * AVATAR_ONLY 잡의 JSON 형태 — ai-server/worker.py의 handle() 라우팅 계약을 검증한다.
 */
class WardrobeServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void enqueueAvatarRebuild_pushesTypedJobToScanQueue() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ListOperations<String, String> listOps = mock(ListOperations.class);
        when(redis.opsForList()).thenReturn(listOps);

        WardrobeService service = new WardrobeService(
                mock(S3Presigner.class), redis, mock(ClothesItemRepository.class),
                new ObjectMapper(), "wardrobe", 300, 20);

        service.enqueueAvatarRebuild(42L);

        ArgumentCaptor<String> job = ArgumentCaptor.forClass(String.class);
        verify(listOps).leftPush(eq(WardrobeService.SCAN_QUEUE), job.capture());
        assertThat(job.getValue()).contains("\"type\":\"AVATAR_ONLY\"");
        assertThat(job.getValue()).contains("\"user_id\":42");
    }
}
