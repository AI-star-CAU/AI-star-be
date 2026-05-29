package com.aistar.backend.domain.chat.dto;

import com.aistar.backend.domain.llm.enums.LlmModel;
import com.aistar.backend.domain.llm.enums.LlmProvider;
import com.aistar.backend.domain.chat.enums.TitleStatus;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;

public class ExplorerResDto {

    @Builder
    public record ExplorerPage(
            List<ExplorerTreeDto> roots,
            int page,
            int size,
            int totalRootCount,
            boolean hasNext
    ) {}

    @Builder
    public record ExplorerTreeDto(
            Long rootChatId,
            List<ExplorerNodeDto> nodes
    ) {}

    @Builder
    public record ExplorerNodeDto(
            Long chatId,
            Long parentChatId,
            Long branchPointTurnId,
            String title,
            TitleStatus titleStatus,
            int depth,
            int turnCount,
            LocalDateTime lastActivityAt,
            LocalDateTime createdAt,
            LocalDateTime deletedAt,
            LlmProvider llmProvider,
            LlmModel llmModel
    ) {}
}

