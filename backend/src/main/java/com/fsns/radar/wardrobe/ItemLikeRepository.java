package com.fsns.radar.wardrobe;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ItemLikeRepository extends JpaRepository<ItemLike, ItemLike.Id> {

    @Query("SELECT c FROM ClothesItem c WHERE c.id IN " +
           "(SELECT l.id.itemId FROM ItemLike l WHERE l.id.userId = :userId)")
    List<ClothesItem> findLikedItems(@Param("userId") Long userId);
}
