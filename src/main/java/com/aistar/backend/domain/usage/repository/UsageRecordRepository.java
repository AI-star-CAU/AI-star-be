package com.aistar.backend.domain.usage.repository;

import com.aistar.backend.domain.usage.entity.UsageRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UsageRecordRepository extends JpaRepository<UsageRecord, Long> {

    @Query("SELECT ur FROM UsageRecord ur JOIN FETCH ur.plan " +
            "WHERE ur.member.id = :memberId AND ur.periodStart <= :now AND ur.periodEnd > :now")
    Optional<UsageRecord> findActiveByMemberId(@Param("memberId") Long memberId,
                                                @Param("now") LocalDateTime now);

    // 가장 최근 usage_record 조회 (활성 record 없을 때 plan 확인용)
    @Query("SELECT ur FROM UsageRecord ur JOIN FETCH ur.plan JOIN FETCH ur.member " +
            "WHERE ur.member.id = :memberId ORDER BY ur.periodEnd DESC")
    List<UsageRecord> findLatestByMemberId(@Param("memberId") Long memberId,
                                           org.springframework.data.domain.Pageable pageable);
}
