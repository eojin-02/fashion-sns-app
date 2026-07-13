package com.fsns.radar.user;

import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserBlockRepository extends JpaRepository<UserBlock, UserBlock.Id> {

    /** 내가 차단한 유저 목록 (마이페이지 차단 관리용) */
    @Query("SELECT b.id.blockedId FROM UserBlock b WHERE b.id.blockerId = :userId")
    java.util.List<Long> findBlockedIdsByBlocker(@Param("userId") Long userId);

    /**
     * 양방향 차단 대상 집합 — 내가 차단했거나 나를 차단한 모든 유저.
     * 레이더·갤러리 노출 필터에 사용 (설계서 1.2: 차단 시 양방향 상호 비노출).
     */
    @Query(value = """
            SELECT blocked_id FROM user_block WHERE blocker_id = :userId
            UNION
            SELECT blocker_id FROM user_block WHERE blocked_id = :userId
            """, nativeQuery = true)
    Set<Long> findAllRelatedUserIds(@Param("userId") Long userId);
}
