package com.fsns.radar.contest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "contest_entry",
       uniqueConstraints = @UniqueConstraint(columnNames = {"contest_id", "user_id"}))  // 1인 1출품
public class ContestEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "contest_id", nullable = false)
    private Long contestId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "codi_id", nullable = false)
    private Long codiId;

    protected ContestEntry() {}

    public Long getId() { return id; }
}
