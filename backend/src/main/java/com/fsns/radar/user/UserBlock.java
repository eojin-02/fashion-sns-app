package com.fsns.radar.user;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "user_block")
public class UserBlock {

    @Embeddable
    public static class Id implements Serializable {
        @Column(name = "blocker_id")
        private Long blockerId;
        @Column(name = "blocked_id")
        private Long blockedId;

        protected Id() {}
        public Id(Long blockerId, Long blockedId) {
            this.blockerId = blockerId;
            this.blockedId = blockedId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Id other
                    && Objects.equals(blockerId, other.blockerId)
                    && Objects.equals(blockedId, other.blockedId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(blockerId, blockedId);
        }
    }

    @EmbeddedId
    private Id id;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected UserBlock() {}

    public UserBlock(Long blockerId, Long blockedId) {
        this.id = new Id(blockerId, blockedId);
    }

    public Id getId() { return id; }
}
