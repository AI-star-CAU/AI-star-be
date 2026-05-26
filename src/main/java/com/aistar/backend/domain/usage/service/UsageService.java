package com.aistar.backend.domain.usage.service;

import com.aistar.backend.domain.usage.converter.UsageConverter;
import com.aistar.backend.domain.usage.dto.UsageResDto;
import com.aistar.backend.domain.usage.entity.UsageRecord;
import com.aistar.backend.domain.usage.repository.UsageRecordRepository;
import com.aistar.backend.global.apiPayload.code.ErrorStatus;
import com.aistar.backend.global.apiPayload.exception.ProjectException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UsageService {

    private final UsageRecordRepository usageRecordRepository;

    @Transactional
    public UsageResDto.UsageInfo getMyUsage(Long memberId) {
        LocalDateTime now = LocalDateTime.now();

        // 1. 활성 record 조회
        return usageRecordRepository.findActiveByMemberId(memberId, now)
                .map(UsageConverter::toUsageInfo)
                .orElseGet(() -> createNewPeriod(memberId, now));
    }

    /**
     * LLM 호출 완료 시 사용량 누적.
     * 활성 record가 없으면 조용히 무시 (메시지 흐름을 깨뜨리지 않음).
     */
    @Transactional
    public void accumulateUsage(Long memberId, int promptTokens, int answerTokens,
                                int compressedTurns) {
        LocalDateTime now = LocalDateTime.now();
        usageRecordRepository.findActiveByMemberId(memberId, now)
                .ifPresent(record -> record.accumulate(promptTokens, answerTokens, compressedTurns));
    }

    /**
     * 활성 record가 없을 때 — 가장 최근 record의 plan으로 새 기간 생성.
     * record가 아예 없으면 USAGE_4041.
     */
    private UsageResDto.UsageInfo createNewPeriod(Long memberId, LocalDateTime now) {
        List<UsageRecord> latest = usageRecordRepository.findLatestByMemberId(
                memberId, PageRequest.of(0, 1));

        if (latest.isEmpty()) {
            throw new ProjectException(ErrorStatus.USAGE_NOT_FOUND);
        }

        UsageRecord prev = latest.get(0);
        LocalDateTime periodStart = now.withDayOfMonth(1).toLocalDate().atStartOfDay();
        LocalDateTime periodEnd = periodStart.plusMonths(1);

        UsageRecord newRecord = UsageRecord.builder()
                .member(prev.getMember())
                .plan(prev.getPlan())
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .tokenLimit(prev.getPlan().getAiLimit())
                .build();
        usageRecordRepository.save(newRecord);

        return UsageConverter.toUsageInfo(newRecord);
    }
}
