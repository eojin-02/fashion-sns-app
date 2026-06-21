package com.fsns.radar.codi;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DailyCodiItemRepository extends JpaRepository<DailyCodiItem, DailyCodiItem.Id> {

    @Query("SELECT i.id.itemId FROM DailyCodiItem i WHERE i.id.codiId = :codiId")
    List<Long> findItemIds(@Param("codiId") Long codiId);

    @Modifying
    @Query("DELETE FROM DailyCodiItem i WHERE i.id.codiId = :codiId")
    void deleteAllByCodiId(@Param("codiId") Long codiId);
}
