package com.fsns.radar.codi;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/** item_ids BIGINT[] 폐기 → N:M 조인 테이블 (설계서 3.1) */
@Entity
@Table(name = "daily_codi_item")
public class DailyCodiItem {

    @Embeddable
    public static class Id implements Serializable {
        @Column(name = "codi_id")
        private Long codiId;
        @Column(name = "item_id")
        private Long itemId;

        protected Id() {}
        public Id(Long codiId, Long itemId) {
            this.codiId = codiId;
            this.itemId = itemId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Id other
                    && Objects.equals(codiId, other.codiId)
                    && Objects.equals(itemId, other.itemId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(codiId, itemId);
        }
    }

    @EmbeddedId
    private Id id;

    protected DailyCodiItem() {}

    public DailyCodiItem(Long codiId, Long itemId) {
        this.id = new Id(codiId, itemId);
    }

    public Id getId() { return id; }
}
