package com.fsns.radar.wardrobe;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClothesItemRepository extends JpaRepository<ClothesItem, Long> {
    List<ClothesItem> findAllByUserIdOrderByCreatedAtDesc(Long userId);
    Optional<ClothesItem> findFirstByUserIdOrderByCreatedAtDesc(Long userId);
}
