package com.fsns.radar.codi;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/** 코디 좋아요 — 카운트만 노출하고 누른 사람 목록은 어디에도 내보내지 않는다 (익명성 설계). */
@Entity
@Table(name = "codi_like")
public class CodiLike {

    @Embeddable
    public static class Id implements Serializable {
        @Column(name = "user_id")
        private Long userId;
        @Column(name = "codi_id")
        private Long codiId;

        protected Id() {}
        public Id(Long userId, Long codiId) {
            this.userId = userId;
            this.codiId = codiId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Id other
                    && Objects.equals(userId, other.userId)
                    && Objects.equals(codiId, other.codiId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, codiId);
        }
    }

    @EmbeddedId
    private Id id;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected CodiLike() {}

    public CodiLike(Long userId, Long codiId) {
        this.id = new Id(userId, codiId);
    }

    public Id getId() { return id; }
}
