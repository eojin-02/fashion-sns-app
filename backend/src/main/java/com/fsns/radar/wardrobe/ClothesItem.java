package com.fsns.radar.wardrobe;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "clothes_item")
public class ClothesItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(length = 30)
    private String category;

    /** 스키마 불확정 속성(색상, 핏, 소재 등)만 JSONB — 관계는 조인 테이블로 (설계서 3.1) */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "meta_data", columnDefinition = "jsonb")
    private Map<String, Object> metaData;

    @Column(name = "brand_info", length = 100)
    private String brandInfo;

    @Column(name = "image_key", nullable = false)
    private String imageKey;

    /** PENDING / DONE / FAILED — 비동기 AI 파이프라인 추적 (설계서 2.6) */
    @Column(name = "scan_status", nullable = false, length = 20)
    private String scanStatus = "PENDING";

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected ClothesItem() {}

    public ClothesItem(Long userId, String imageKey) {
        this.userId = userId;
        this.imageKey = imageKey;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getCategory() { return category; }
    public Map<String, Object> getMetaData() { return metaData; }
    public String getBrandInfo() { return brandInfo; }
    public String getImageKey() { return imageKey; }
    public String getScanStatus() { return scanStatus; }
    public Instant getCreatedAt() { return createdAt; }

    public String summary() {
        if (category == null) {
            return "분석 중";
        }
        return brandInfo == null ? category : brandInfo + " " + category;
    }
}
