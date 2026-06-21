package com.fsns.radar.codi;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "daily_codi")
public class DailyCodi {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected DailyCodi() {}

    public DailyCodi(Long userId) {
        this.userId = userId;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void touch() {
        this.updatedAt = Instant.now();
    }
}
