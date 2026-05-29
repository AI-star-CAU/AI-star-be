package com.aistar.backend.domain.chat.service.graph;

import com.aistar.backend.domain.chat.dto.GraphResDto;
import com.aistar.backend.domain.chat.entity.Chat;
import com.aistar.backend.domain.chat.entity.Turn;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class GraphNodeMapper {

    public GraphResDto.ChatNodeDto toChatNodeDto(Chat chat, Map<Long, Chat> chatMap) {
        return GraphResDto.ChatNodeDto.builder()
                .chatId(chat.getId())
                .title(chat.getTitle())
                .titleStatus(chat.getTitleStatus())
                .parentChatId(chat.getParentId())
                .branchPointTurnId(chat.getBranchPointTurnId())
                .depth(calculateDepth(chat, chatMap))
                .isDeleted(chat.getDeletedAt() != null)
                .lastTurnId(chat.getLastTurnId())
                .updatedAt(chat.getUpdatedAt())
                .build();
    }

    public GraphResDto.TurnNodeDto toTurnNodeDto(Turn turn, Long centerTurnId,
                                                  Map<Long, List<Chat>> branchPointMap) {
        return GraphResDto.TurnNodeDto.builder()
                .turnId(turn.getId())
                .chatId(turn.getChat().getId())
                .turnSequence(turn.getTurnSequence())
                .summary(turn.getSummary())
                .summaryStatus(turn.getSummary() == null ? "PENDING" : "GENERATED")
                .isBranchPoint(branchPointMap.containsKey(turn.getId()))
                .isCurrent(turn.getId().equals(centerTurnId))
                .createdAt(turn.getCreatedAt())
                .build();
    }

    public GraphResDto.GraphResult buildEmptyResult(Long rootChatId, List<Chat> allChats,
                                                    Map<Long, Chat> chatMap) {
        return GraphResDto.GraphResult.builder()
                .rootChatId(rootChatId)
                .center(null)
                .chats(allChats.stream().map(c -> toChatNodeDto(c, chatMap)).toList())
                .turns(List.of())
                .frontier(GraphResDto.FrontierDto.builder().up(List.of()).down(List.of()).build())
                .build();
    }

    private int calculateDepth(Chat chat, Map<Long, Chat> chatMap) {
        int depth = 0;
        Long parentId = chat.getParentId();
        while (parentId != null) {
            depth++;
            Chat parent = chatMap.get(parentId);
            if (parent == null) {
                break;
            }
            parentId = parent.getParentId();
        }
        return depth;
    }
}
