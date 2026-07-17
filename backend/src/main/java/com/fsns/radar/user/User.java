package com.fsns.radar.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String email;

    @Column(nullable = false, length = 30)
    private String nickname;

    @Column(name = "avatar_url")
    private String avatarUrl;

    /** 3D 아바타 GLB의 S3 키 — AI 워커가 스캔 완료 시 재생성·갱신 (설계서 4.2) */
    @Column(name = "avatar_bundle_key")
    private String avatarBundleKey;

    /** 아바타 베이스 파라미터(피부/헤어) — 가입 시 1회 생성, 이후 변경 가능 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "avatar_config", columnDefinition = "jsonb")
    private Map<String, Object> avatarConfig;

    /** 고스트 모드: 기본 비노출(opt-in) — 설계서 1.2 */
    @Column(name = "radar_visible", nullable = false)
    private boolean radarVisible = false;

    @Column(name = "birth_date", nullable = false)
    private LocalDate birthDate;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected User() {}

    public User(String email, String nickname, LocalDate birthDate) {
        this.email = email;
        this.nickname = nickname;
        this.birthDate = birthDate;
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getNickname() { return nickname; }
    public String getAvatarUrl() { return avatarUrl; }
    public String getAvatarBundleKey() { return avatarBundleKey; }
    public Map<String, Object> getAvatarConfig() { return avatarConfig; }
    public boolean isRadarVisible() { return radarVisible; }
    public LocalDate getBirthDate() { return birthDate; }
    public Instant getCreatedAt() { return createdAt; }

    public void setNickname(String nickname) { this.nickname = nickname; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
    public void setRadarVisible(boolean radarVisible) { this.radarVisible = radarVisible; }
    public void setAvatarConfig(Map<String, Object> avatarConfig) { this.avatarConfig = avatarConfig; }
}
