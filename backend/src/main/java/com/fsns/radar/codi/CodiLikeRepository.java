package com.fsns.radar.codi;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CodiLikeRepository extends JpaRepository<CodiLike, CodiLike.Id> {
    long countByIdCodiId(Long codiId);
    void deleteAllByIdCodiId(Long codiId);
}
