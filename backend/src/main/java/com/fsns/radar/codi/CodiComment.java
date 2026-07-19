package com.fsns.radar.codi;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 코디 댓글. 작성자는 닉네임으로만 노출되고 프로필 연결(세션 밖 재식별)은 제공하지 않는다.
 * 코디가 교체되면 좋아요와 함께 전부 삭제된다 — 어제 착장에 대한 댓글이 오늘 착장에 남지 않게.
 */
@Entity
@Table(name = "codi_comment")
public class CodiComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "codi_id", nullable = false)
    private Long codiId;

    @Column(name = "author_id", nullable = false)
    private Long authorId;

    @Column(nullable = false, length = 200)
    private String content;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected CodiComment() {}

    public CodiComment(Long codiId, Long authorId, String content) {
        this.codiId = codiId;
        this.authorId = authorId;
        this.content = content;
    }

    public Long getId() { return id; }
    public Long getCodiId() { return codiId; }
    public Long getAuthorId() { return authorId; }
    public String getContent() { return content; }
    public Instant getCreatedAt() { return createdAt; }
}
