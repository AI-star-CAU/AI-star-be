package com.aistar.backend.domain.chat.dto;

import com.aistar.backend.domain.chat.enums.LlmModel;
import com.aistar.backend.domain.chat.enums.LlmProvider;
import lombok.Builder;

import java.time.LocalDateTime;

public class ChatResDto {

    @Builder
    public record Detail(
            Long chatId,
            Long rootChatId,
            Long parentId,
            String title,
            LlmProvider llmProvider,
            LlmModel llmModel,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {}

    @Builder
    public record ListItem(
            Long chatId,
            String title,
            LlmProvider llmProvider,
            LlmModel llmModel,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {}

    @Builder
    public record PageInfo(
            Object content,
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {}
}
