package com.fsns.radar.wardrobe;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "item_like")
public class ItemLike {

    @Embeddable
    public static class Id implements Serializable {
        @Column(name = "user_id")
        private Long userId;
        @Column(name = "item_id")
        private Long itemId;

        protected Id() {}
        public Id(Long userId, Long itemId) {
            this.userId = userId;
            this.itemId = itemId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Id other
                    && Objects.equals(userId, other.userId)
                    && Objects.equals(itemId, other.itemId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, itemId);
        }
    }

    @EmbeddedId
    private Id id;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected ItemLike() {}

    public ItemLike(Long userId, Long itemId) {
        this.id = new Id(userId, itemId);
    }

    public Id getId() { return id; }
}
