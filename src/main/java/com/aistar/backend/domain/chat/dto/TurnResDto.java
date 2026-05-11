package com.aistar.backend.domain.chat.dto;

import com.aistar.backend.domain.chat.enums.MessageStatus;
import com.aistar.backend.domain.chat.enums.SenderType;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;

public class TurnResDto {

    @Builder
    public record TurnPage(
            List<TurnItem> turns,
            Integer nextTurnSequence,
            boolean hasMore
    ) {}

    @Builder
    public record TurnItem(
            Long turnId,
            Integer turnSequence,
            String summary,
            List<MessageItem> messages,
            LocalDateTime createdAt
    ) {}

    @Builder
    public record MessageItem(
            Long messageId,
            SenderType senderType,
            MessageStatus status,
            String content,
            Integer promptToken,
            Integer answerToken,
            LocalDateTime createdAt
    ) {}
}
