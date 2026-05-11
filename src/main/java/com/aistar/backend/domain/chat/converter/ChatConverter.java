package com.aistar.backend.domain.chat.converter;

import com.aistar.backend.domain.chat.dto.ChatResDto;
import com.aistar.backend.domain.chat.entity.Chat;
import org.springframework.data.domain.Page;

import java.util.List;

public class ChatConverter {

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

    public static ChatResDto.ListItem toListItem(Chat chat) {
        return ChatResDto.ListItem.builder()
                .chatId(chat.getId())
                .title(chat.getTitle())
                .llmProvider(chat.getLlmProvider())
                .llmModel(chat.getLlmModel())
                .createdAt(chat.getCreatedAt())
                .updatedAt(chat.getUpdatedAt())
                .build();
    }

    public static ChatResDto.PageInfo toPageInfo(Page<Chat> page) {
        List<ChatResDto.ListItem> content = page.getContent().stream()
                .map(ChatConverter::toListItem)
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
