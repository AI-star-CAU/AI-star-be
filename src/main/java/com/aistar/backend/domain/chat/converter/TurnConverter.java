package com.aistar.backend.domain.chat.converter;

import com.aistar.backend.domain.chat.dto.TurnResDto;
import com.aistar.backend.domain.chat.entity.Message;
import com.aistar.backend.domain.chat.entity.Turn;

import java.util.List;

public class TurnConverter {

    public static TurnResDto.TurnItem toTurnItem(Turn turn) {
        List<TurnResDto.MessageItem> messages = turn.getMessages().stream()
                .map(TurnConverter::toMessageItem)
                .toList();

        return TurnResDto.TurnItem.builder()
                .turnId(turn.getId())
                .turnSequence(turn.getTurnSequence())
                .summary(turn.getSummary())
                .messages(messages)
                .createdAt(turn.getCreatedAt())
                .build();
    }

    public static TurnResDto.MessageItem toMessageItem(Message message) {
        return TurnResDto.MessageItem.builder()
                .chatId(message.getTurn().getChat().getId())
                .messageId(message.getId())
                .senderType(message.getSenderType())
                .status(message.getStatus())
                .content(message.getContent())
                .promptToken(message.getPromptToken())
                .answerToken(message.getAnswerToken())
                .createdAt(message.getCreatedAt())
                .build();
    }
}
