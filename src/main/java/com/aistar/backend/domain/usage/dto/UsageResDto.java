package com.aistar.backend.domain.usage.dto;

import com.aistar.backend.domain.usage.enums.WarningLevel;
import lombok.Builder;

import java.time.LocalDateTime;

public class UsageResDto {

    @Builder
    public record UsageInfo(
            Long usageRecordId,
            Long planId,
            String planName,
            LocalDateTime periodStart,
            LocalDateTime periodEnd,
            Long tokensUsed,
            Long tokenLimit,
            Integer requestCount,
            Long remainingTokens,
            Double usageRatio,
            WarningLevel warningLevel
    ) {}
}
