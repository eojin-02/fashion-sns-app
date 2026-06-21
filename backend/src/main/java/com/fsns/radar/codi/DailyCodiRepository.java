package com.fsns.radar.codi;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DailyCodiRepository extends JpaRepository<DailyCodi, Long> {
    Optional<DailyCodi> findByUserId(Long userId);
}
