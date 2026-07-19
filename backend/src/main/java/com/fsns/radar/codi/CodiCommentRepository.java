package com.fsns.radar.codi;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CodiCommentRepository extends JpaRepository<CodiComment, Long> {
    List<CodiComment> findAllByCodiIdOrderByIdAsc(Long codiId);
    long countByCodiId(Long codiId);
    void deleteAllByCodiId(Long codiId);
}
