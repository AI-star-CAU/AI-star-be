package com.aistar.backend.domain.chat.converter;

import com.aistar.backend.domain.chat.dto.ExplorerResDto;
import com.aistar.backend.domain.chat.entity.Chat;
import org.springframework.data.domain.Page;

import java.util.*;
import java.util.stream.Collectors;

public class ExplorerConverter {

    public static ExplorerResDto.ExplorerPage toExplorerPage(
            Page<Chat> rootPage,
            List<Chat> allChats,
            Map<Long, Long> turnCounts) {

        Map<Long, List<Chat>> chatsByRoot = allChats.stream()
                .collect(Collectors.groupingBy(Chat::getRootChatId));

        List<ExplorerResDto.ExplorerTreeDto> roots = rootPage.getContent().stream()
                .map(root -> toTree(root.getId(),
                        chatsByRoot.getOrDefault(root.getId(), List.of()),
                        turnCounts))
                .toList();

        return ExplorerResDto.ExplorerPage.builder()
                .roots(roots)
                .page(rootPage.getNumber())
                .size(rootPage.getSize())
                .totalRootCount((int) rootPage.getTotalElements())
                .hasNext(rootPage.hasNext())
                .build();
    }

    public static ExplorerResDto.ExplorerTreeDto toTree(
            Long rootChatId,
            List<Chat> chats,
            Map<Long, Long> turnCounts) {

        Map<Long, List<Chat>> childrenMap = chats.stream()
                .filter(c -> c.getParentId() != null)
                .collect(Collectors.groupingBy(Chat::getParentId));

        Chat rootChat = chats.stream()
                .filter(c -> c.getParentId() == null)
                .findFirst()
                .orElse(null);

        if (rootChat == null) {
            return ExplorerResDto.ExplorerTreeDto.builder()
                    .rootChatId(rootChatId)
                    .nodes(List.of())
                    .build();
        }

        List<ExplorerResDto.ExplorerNodeDto> nodes = new ArrayList<>();
        dfs(rootChat, 0, childrenMap, turnCounts, nodes);

        return ExplorerResDto.ExplorerTreeDto.builder()
                .rootChatId(rootChatId)
                .nodes(nodes)
                .build();
    }

    private static void dfs(Chat chat, int depth,
                            Map<Long, List<Chat>> childrenMap,
                            Map<Long, Long> turnCounts,
                            List<ExplorerResDto.ExplorerNodeDto> nodes) {
        nodes.add(toNode(chat, depth, turnCounts));

        List<Chat> children = childrenMap.getOrDefault(chat.getId(), List.of());
        children.stream()
                .sorted(Comparator.comparing(Chat::getCreatedAt))
                .forEach(child -> dfs(child, depth + 1, childrenMap, turnCounts, nodes));
    }

    private static ExplorerResDto.ExplorerNodeDto toNode(
            Chat chat, int depth, Map<Long, Long> turnCounts) {
        return ExplorerResDto.ExplorerNodeDto.builder()
                .chatId(chat.getId())
                .parentChatId(chat.getParentId())
                .branchPointTurnId(chat.getBranchPointTurnId())
                .title(chat.getTitle())
                .titleStatus(chat.getTitleStatus())
                .depth(depth)
                .turnCount(turnCounts.getOrDefault(chat.getId(), 0L).intValue())
                .lastActivityAt(chat.getLastActivityAt())
                .createdAt(chat.getCreatedAt())
                .deletedAt(chat.getDeletedAt())
                .llmProvider(chat.getLlmProvider())
                .llmModel(chat.getLlmModel())
                .build();
    }
}
