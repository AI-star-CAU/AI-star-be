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
            Long turnId,
            Long aiMessageId,
            Integer answerToken,
            String summaryStatus
    ) {}

    @Builder
    public record SseError(
            String code,
            String message,
            boolean retryable
    ) {}

    public record Done() {}

    @Builder
    public record CancelResult(
            Long messageId,
            MessageStatus status,
            String content,
            Integer answerToken
    ) {}

    @Builder
    public record Cancelled(
            Long aiMessageId,
            MessageStatus status,
            String content,
            Integer answerToken
    ) {}
}
