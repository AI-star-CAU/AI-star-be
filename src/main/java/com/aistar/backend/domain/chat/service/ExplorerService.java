package com.aistar.backend.domain.chat.service;

import com.aistar.backend.domain.chat.converter.ExplorerConverter;
import com.aistar.backend.domain.chat.dto.ExplorerResDto;
import com.aistar.backend.domain.chat.entity.Chat;
import com.aistar.backend.domain.chat.enums.ExplorerSort;
import com.aistar.backend.domain.chat.repository.ChatRepository;
import com.aistar.backend.domain.chat.repository.TurnRepository;
import com.aistar.backend.global.apiPayload.code.ErrorStatus;
import com.aistar.backend.global.apiPayload.exception.ProjectException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ExplorerService {

    private final ChatRepository chatRepository;
    private final TurnRepository turnRepository;

    @Transactional(readOnly = true)
    public ExplorerResDto.ExplorerPage getExplorerPage(
            Long memberId, ExplorerSort sort, int page, int size, boolean includeDeleted) {

        Pageable pageable = PageRequest.of(page, size);

        Page<Chat> rootPage = switch (sort) {
            case RECENT -> chatRepository.findRootsByRecent(memberId, includeDeleted, pageable);
            case CREATED -> chatRepository.findRootsByCreated(memberId, includeDeleted, pageable);
            case NAME -> chatRepository.findRootsByName(memberId, includeDeleted, pageable);
        };

        if (rootPage.isEmpty()) {
            return ExplorerConverter.toExplorerPage(rootPage, List.of(), Map.of());
        }

        List<Long> rootIds = rootPage.getContent().stream().map(Chat::getId).toList();
        List<Chat> allChats = chatRepository.findAllByRootChatIdIn(rootIds, includeDeleted);

        List<Long> allChatIds = allChats.stream().map(Chat::getId).toList();
        Map<Long, Long> turnCounts = turnRepository.countByChatIds(allChatIds).stream()
                .collect(Collectors.toMap(r -> (Long) r[0], r -> (Long) r[1]));

        return ExplorerConverter.toExplorerPage(rootPage, allChats, turnCounts);
    }

    @Transactional(readOnly = true)
    public ExplorerResDto.ExplorerTreeDto getExplorerTree(
            Long memberId, Long rootChatId, boolean includeDeleted) {

        Chat rootChat = chatRepository.findById(rootChatId)
                .orElseThrow(() -> new ProjectException(ErrorStatus.CHAT_NOT_FOUND));

        if (!rootChat.getMember().getId().equals(memberId)) {
            throw new ProjectException(ErrorStatus.FORBIDDEN);
        }

        if (rootChat.getParentId() != null) {
            throw new ProjectException(ErrorStatus.EXPLORER_INVALID_PARAM);
        }

        List<Chat> allChats = includeDeleted
                ? chatRepository.findAllByRootChatId(rootChatId)
                : chatRepository.findAllByRootChatIdAndDeletedAtIsNull(rootChatId);

        List<Long> chatIds = allChats.stream().map(Chat::getId).toList();
        Map<Long, Long> turnCounts = chatIds.isEmpty() ? Map.of()
                : turnRepository.countByChatIds(chatIds).stream()
                        .collect(Collectors.toMap(r -> (Long) r[0], r -> (Long) r[1]));

        return ExplorerConverter.toTree(rootChatId, allChats, turnCounts);
    }
}
