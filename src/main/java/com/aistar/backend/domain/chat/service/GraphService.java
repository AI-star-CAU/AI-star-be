package com.aistar.backend.domain.chat.service;

import com.aistar.backend.domain.chat.dto.GraphResDto;
import com.aistar.backend.domain.chat.entity.Chat;
import com.aistar.backend.domain.chat.entity.Turn;
import com.aistar.backend.domain.chat.repository.ChatRepository;
import com.aistar.backend.domain.chat.repository.TurnRepository;
import com.aistar.backend.domain.chat.service.graph.FrontierCalculator;
import com.aistar.backend.domain.chat.service.graph.GraphNodeMapper;
import com.aistar.backend.domain.chat.service.graph.TurnTraverser;
import com.aistar.backend.global.apiPayload.code.ErrorStatus;
import com.aistar.backend.global.apiPayload.exception.ProjectException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GraphService {

    private final ChatRepository chatRepository;
    private final TurnRepository turnRepository;
    private final TurnTraverser turnTraverser;
    private final FrontierCalculator frontierCalculator;
    private final GraphNodeMapper graphNodeMapper;

    @Transactional(readOnly = true)
    public GraphResDto.GraphResult getGraph(Long memberId, Long chatId,
                                            Long centerTurnId, int up, int down,
                                            boolean includeDeleted) {
        if (up < 1 || up > 100 || down < 1 || down > 100) {
            throw new ProjectException(ErrorStatus.GRAPH_INVALID_PARAM);
        }

        Chat pathChat = chatRepository.findByIdWithMemberAndDeletedAtIsNull(chatId)
                .orElseThrow(() -> new ProjectException(ErrorStatus.CHAT_NOT_FOUND));
        validateOwner(pathChat, memberId);

        Long rootChatId = pathChat.getRootChatId();
        List<Chat> allChats = includeDeleted
                ? chatRepository.findAllByRootChatId(rootChatId)
                : chatRepository.findAllByRootChatIdAndDeletedAtIsNull(rootChatId);

        Map<Long, Chat> chatMap = allChats.stream()
                .collect(Collectors.toMap(Chat::getId, c -> c));
        Map<Long, List<Chat>> branchPointMap = allChats.stream()
                .filter(c -> c.getBranchPointTurnId() != null)
                .collect(Collectors.groupingBy(Chat::getBranchPointTurnId));

        Turn centerTurn = turnTraverser.resolveCenterTurn(pathChat, centerTurnId);
        if (centerTurn == null) {
            return graphNodeMapper.buildEmptyResult(rootChatId, allChats, chatMap);
        }

        if (centerTurnId != null && !chatMap.containsKey(centerTurn.getChat().getId())) {
            throw new ProjectException(ErrorStatus.GRAPH_INVALID_PARAM);
        }

        List<Turn> upTurns = turnTraverser.traceUp(centerTurn, up, chatMap);
        List<Turn> downTurns = turnTraverser.traceDown(centerTurn, down, branchPointMap);
        List<Turn> windowTurns = mergeWindowTurns(centerTurn, upTurns, downTurns);

        GraphResDto.FrontierDto frontier = frontierCalculator.calculateForGraph(
                centerTurn, upTurns, downTurns, chatMap);

        List<GraphResDto.ChatNodeDto> chatNodes = allChats.stream()
                .map(c -> graphNodeMapper.toChatNodeDto(c, chatMap))
                .toList();
        List<GraphResDto.TurnNodeDto> turnNodes = windowTurns.stream()
                .map(t -> graphNodeMapper.toTurnNodeDto(t, centerTurn.getId(), branchPointMap))
                .toList();

        return GraphResDto.GraphResult.builder()
                .rootChatId(rootChatId)
                .center(GraphResDto.CenterDto.builder()
                        .turnId(centerTurn.getId())
                        .chatId(centerTurn.getChat().getId())
                        .build())
                .chats(chatNodes)
                .turns(turnNodes)
                .frontier(frontier)
                .build();
    }

    @Transactional(readOnly = true)
    public GraphResDto.ExpandResult expandWindow(Long memberId, Long chatId,
                                                 Long fromTurnId, String direction,
                                                 int limit, boolean includeDeleted) {
        if (limit < 1 || limit > 100) {
            throw new ProjectException(ErrorStatus.GRAPH_INVALID_PARAM);
        }
        if (!"UP".equals(direction) && !"DOWN".equals(direction)) {
            throw new ProjectException(ErrorStatus.GRAPH_INVALID_PARAM);
        }

        Chat pathChat = chatRepository.findByIdWithMemberAndDeletedAtIsNull(chatId)
                .orElseThrow(() -> new ProjectException(ErrorStatus.CHAT_NOT_FOUND));
        validateOwner(pathChat, memberId);

        Long rootChatId = pathChat.getRootChatId();
        List<Chat> allChats = includeDeleted
                ? chatRepository.findAllByRootChatId(rootChatId)
                : chatRepository.findAllByRootChatIdAndDeletedAtIsNull(rootChatId);

        Map<Long, Chat> chatMap = allChats.stream()
                .collect(Collectors.toMap(Chat::getId, c -> c));

        Turn fromTurn = turnRepository.findById(fromTurnId)
                .orElseThrow(() -> new ProjectException(ErrorStatus.TURN_NOT_FOUND));
        if (!chatMap.containsKey(fromTurn.getChat().getId())) {
            throw new ProjectException(ErrorStatus.GRAPH_INVALID_PARAM);
        }

        Map<Long, List<Chat>> branchPointMap = allChats.stream()
                .filter(c -> c.getBranchPointTurnId() != null)
                .collect(Collectors.groupingBy(Chat::getBranchPointTurnId));

        List<Turn> turns;
        GraphResDto.FrontierDto frontier;
        if ("UP".equals(direction)) {
            turns = turnTraverser.traceUp(fromTurn, limit, chatMap);
            frontier = frontierCalculator.calculateForExpandUp(turns, chatMap);
        } else {
            turns = turnTraverser.traceDown(fromTurn, limit, branchPointMap);
            frontier = frontierCalculator.calculateForExpandDown(turns);
        }

        List<GraphResDto.TurnNodeDto> turnNodes = turns.stream()
                .map(t -> graphNodeMapper.toTurnNodeDto(t, null, branchPointMap))
                .toList();

        return GraphResDto.ExpandResult.builder()
                .direction(direction)
                .turns(turnNodes)
                .frontier(frontier)
                .build();
    }

    private List<Turn> mergeWindowTurns(Turn centerTurn, List<Turn> upTurns, List<Turn> downTurns) {
        Set<Long> seen = new LinkedHashSet<>();
        List<Turn> windowTurns = new ArrayList<>();

        seen.add(centerTurn.getId());
        windowTurns.add(centerTurn);
        for (Turn turn : upTurns) {
            if (seen.add(turn.getId())) {
                windowTurns.add(turn);
            }
        }
        for (Turn turn : downTurns) {
            if (seen.add(turn.getId())) {
                windowTurns.add(turn);
            }
        }
        return windowTurns;
    }

    private void validateOwner(Chat chat, Long memberId) {
        if (!chat.getMember().getId().equals(memberId)) {
            throw new ProjectException(ErrorStatus.FORBIDDEN);
        }
    }
}
