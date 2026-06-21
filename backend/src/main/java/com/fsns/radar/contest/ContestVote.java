package com.fsns.radar.contest;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "contest_vote")
public class ContestVote {

    @Embeddable
    public static class Id implements Serializable {
        @Column(name = "entry_id")
        private Long entryId;
        @Column(name = "voter_id")
        private Long voterId;

        protected Id() {}

        @Override
        public boolean equals(Object o) {
            return o instanceof Id other
                    && Objects.equals(entryId, other.entryId)
                    && Objects.equals(voterId, other.voterId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(entryId, voterId);
        }
    }

    @EmbeddedId
    private Id id;  // 1인 1표

    protected ContestVote() {}
}
