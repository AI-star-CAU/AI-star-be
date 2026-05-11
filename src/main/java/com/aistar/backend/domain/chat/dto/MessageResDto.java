package com.aistar.backend.domain.chat.dto;

import com.aistar.backend.domain.chat.enums.MessageStatus;
import lombok.Builder;

public class MessageResDto {

    @Builder
    public record TurnStarted(
            Long turnId,
            Long userMessageId,
            Long aiMessageId
    ) {}

    @Builder
    public record Chunk(
            String text
    ) {}

    @Builder
    public record TurnCompleted(
            Long aiMessageId,
            String summary,
            Integer answerToken
    ) {}

    @Builder
    public record SseError(
            String code,
            String message
    ) {}

    @Builder
    public record CancelResult(
            Long messageId,
            MessageStatus status,
            String content,
            Integer answerToken
    ) {}
}
