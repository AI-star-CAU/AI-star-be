package com.aistar.backend.domain.chat.event;

public record MessageCompletedEvent(
        Long memberId,
        Long chatId,
        Long turnId,
        Integer contextTokens,
        Integer answerToken,
        Integer compressedTurnCount,
        String fullContent,
        String userContent
) {}
