package com.aistar.backend.domain.usage.converter;

import com.aistar.backend.domain.usage.dto.UsageResDto;
import com.aistar.backend.domain.usage.entity.UsageRecord;
import com.aistar.backend.domain.usage.enums.WarningLevel;

public class UsageConverter {

    public static UsageResDto.UsageInfo toUsageInfo(UsageRecord record) {
        long tokenLimit = record.getTokenLimit();
        long tokensUsed = record.getTokensUsed();

        Long remainingTokens = tokenLimit == 0 ? null : tokenLimit - tokensUsed;
        Double usageRatio = tokenLimit == 0 ? null : (double) tokensUsed / tokenLimit;

        return UsageResDto.UsageInfo.builder()
                .usageRecordId(record.getId())
                .planId(record.getPlan().getId())
                .planName(record.getPlan().getName())
                .periodStart(record.getPeriodStart())
                .periodEnd(record.getPeriodEnd())
                .tokensUsed(tokensUsed)
                .tokenLimit(tokenLimit)
                .requestCount(record.getRequestCount())
                .remainingTokens(remainingTokens)
                .usageRatio(usageRatio)
                .warningLevel(WarningLevel.from(usageRatio))
                .build();
    }
}
