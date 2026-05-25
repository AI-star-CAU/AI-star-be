package com.aistar.backend.domain.usage.repository;

import com.aistar.backend.domain.usage.entity.UsageRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface UsageRecordRepository extends JpaRepository<UsageRecord, Long> {

    @Query("SELECT ur FROM UsageRecord ur JOIN FETCH ur.plan " +
            "WHERE ur.member.id = :memberId AND ur.periodStart <= :now AND ur.periodEnd > :now")
    Optional<UsageRecord> findActiveByMemberId(@Param("memberId") Long memberId,
                                                @Param("now") LocalDateTime now);
}
