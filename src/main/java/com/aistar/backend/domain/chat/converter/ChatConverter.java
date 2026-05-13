package com.aistar.backend.domain.chat.converter;

import com.aistar.backend.domain.chat.dto.ChatResDto;
import com.aistar.backend.domain.chat.entity.Chat;
import com.aistar.backend.domain.chat.entity.Message;
import org.springframework.data.domain.Page;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public class ChatConverter {

    private static final int PREVIEW_MAX_LENGTH = 100;

    public static ChatResDto.Detail toDetail(Chat chat) {
        return ChatResDto.Detail.builder()
                .chatId(chat.getId())
                .rootChatId(chat.getRootChatId())
                .parentId(chat.getParentId())
                .title(chat.getTitle())
                .llmProvider(chat.getLlmProvider())
                .llmModel(chat.getLlmModel())
                .createdAt(chat.getCreatedAt())
                .updatedAt(chat.getUpdatedAt())
                .build();
    }

    public static ChatResDto.ListItem toListItem(Chat chat,
                                                  Map<Long, Long> turnCounts,
                                                  Map<Long, Message> latestMessages) {
        Long chatId = chat.getId();
        int turnCount = turnCounts.getOrDefault(chatId, 0L).intValue();

        String preview = null;
        LocalDateTime lastMessageAt = null;
        Message latest = latestMessages.get(chatId);
        if (latest != null) {
            String content = latest.getContent();
            if (content != null) {
                preview = content.length() > PREVIEW_MAX_LENGTH
                        ? content.substring(0, PREVIEW_MAX_LENGTH) : content;
            }
            lastMessageAt = latest.getCreatedAt();
        }

        return ChatResDto.ListItem.builder()
                .chatId(chatId)
                .title(chat.getTitle())
                .lastMessagePreview(preview)
                .turnCount(turnCount)
                .lastMessageAt(lastMessageAt)
                .llmProvider(chat.getLlmProvider())
                .llmModel(chat.getLlmModel())
                .createdAt(chat.getCreatedAt())
                .updatedAt(chat.getUpdatedAt())
                .build();
    }

    public static ChatResDto.PageInfo toPageInfo(Page<Chat> page,
                                                 Map<Long, Long> turnCounts,
                                                 Map<Long, Message> latestMessages) {
        List<ChatResDto.ListItem> content = page.getContent().stream()
                .map(chat -> toListItem(chat, turnCounts, latestMessages))
                .toList();

        return ChatResDto.PageInfo.builder()
                .content(content)
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .build();
    }
}
