package com.fsns.radar.wardrobe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fsns.radar.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * 인프라 없이 순수 JVM에서 도는 단위 테스트.
 * 스캔/아바타 잡의 JSON 계약(ai-server/worker.py의 handle() 라우팅)과
 * 상품 URL 형식 검증을 확인한다.
 */
class WardrobeServiceTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ClothesItemRepository repository = mock(ClothesItemRepository.class);

    @SuppressWarnings("unchecked")
    private final ListOperations<String, String> listOps = mock(ListOperations.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);

    private final WardrobeService service = new WardrobeService(
            mock(S3Presigner.class), redis, repository,
            new ObjectMapper(), "wardrobe", 300, 20);

    @BeforeEach
    void setUp() {
        when(redis.opsForList()).thenReturn(listOps);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(1L);
        when(repository.save(any(ClothesItem.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void enqueueAvatarRebuild_pushesTypedJobToScanQueue() {
        service.enqueueAvatarRebuild(42L);

        ArgumentCaptor<String> job = ArgumentCaptor.forClass(String.class);
        verify(listOps).leftPush(eq(WardrobeService.SCAN_QUEUE), job.capture());
        assertThat(job.getValue()).contains("\"type\":\"AVATAR_ONLY\"");
        assertThat(job.getValue()).contains("\"user_id\":42");
    }

    @Test
    void enqueueScan_withProductUrl_savesAndForwardsToJob() {
        ClothesItem item = service.enqueueScan(1L, "u1/photo.jpg",
                "  https://store.example.com/item/99  ");

        assertThat(item.getProductUrl()).isEqualTo("https://store.example.com/item/99");
        ArgumentCaptor<String> job = ArgumentCaptor.forClass(String.class);
        verify(listOps).leftPush(eq(WardrobeService.SCAN_QUEUE), job.capture());
        assertThat(job.getValue()).contains("\"product_url\":\"https://store.example.com/item/99\"");
    }

    @Test
    void enqueueScan_withoutProductUrl_omitsFieldFromJob() {
        ClothesItem item = service.enqueueScan(1L, "u1/photo.jpg", "  ");

        assertThat(item.getProductUrl()).isNull();
        ArgumentCaptor<String> job = ArgumentCaptor.forClass(String.class);
        verify(listOps).leftPush(eq(WardrobeService.SCAN_QUEUE), job.capture());
        assertThat(job.getValue()).doesNotContain("product_url");
    }

    @Test
    void enqueueScan_rejectsNonHttpProductUrl() {
        for (String bad : new String[]{"javascript:alert(1)", "ftp://x.com/a", "무신사에서 삼"}) {
            assertThatThrownBy(() -> service.enqueueScan(1L, "u1/photo.jpg", bad))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).getStatus())
                            .isEqualTo(HttpStatus.BAD_REQUEST));
        }
        verify(repository, never()).save(any());
    }
}
