package com.aistar.backend.domain.chat.service;

import com.aistar.backend.domain.chat.dto.GraphResDto;
import com.aistar.backend.domain.chat.entity.Chat;
import com.aistar.backend.domain.chat.entity.Turn;
import com.aistar.backend.domain.chat.repository.ChatRepository;
import com.aistar.backend.domain.chat.repository.TurnRepository;
import com.aistar.backend.global.apiPayload.code.ErrorStatus;
import com.aistar.backend.global.apiPayload.exception.ProjectException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GraphService {

    private final ChatRepository chatRepository;
    private final TurnRepository turnRepository;

    @Transactional(readOnly = true)
    public GraphResDto.GraphResult getGraph(Long memberId, Long chatId,
                                             Long centerTurnId, int up, int down,
                                             boolean includeDeleted) {
        if (up < 1 || up > 100 || down < 1 || down > 100) {
            throw new ProjectException(ErrorStatus.GRAPH_INVALID_PARAM);
        }

        Chat pathChat = chatRepository.findByIdAndDeletedAtIsNull(chatId)
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

        Turn centerTurn = resolveCenterTurn(pathChat, centerTurnId, chatMap);

        if (centerTurn == null) {
            return buildEmptyResult(rootChatId, allChats, chatMap);
        }

        // centerTurnId 명시 시 트리 소속 검증
        if (centerTurnId != null && !chatMap.containsKey(centerTurn.getChat().getId())) {
            throw new ProjectException(ErrorStatus.GRAPH_INVALID_PARAM);
        }

        List<Turn> upTurns = traceUp(centerTurn, up, chatMap);
        List<Turn> downTurns = traceDown(centerTurn, down, branchPointMap);

        // 중복 제거하며 합치기
        Set<Long> seen = new LinkedHashSet<>();
        List<Turn> windowTurns = new ArrayList<>();

        seen.add(centerTurn.getId());
        windowTurns.add(centerTurn);
        for (Turn t : upTurns) {
            if (seen.add(t.getId())) windowTurns.add(t);
        }
        for (Turn t : downTurns) {
            if (seen.add(t.getId())) windowTurns.add(t);
        }

        GraphResDto.FrontierDto frontier = calculateFrontier(centerTurn, upTurns, downTurns, chatMap);

        List<GraphResDto.ChatNodeDto> chatNodes = allChats.stream()
                .map(c -> toChatNodeDto(c, chatMap))
                .toList();

        List<GraphResDto.TurnNodeDto> turnNodes = windowTurns.stream()
                .map(t -> toTurnNodeDto(t, centerTurn.getId(), branchPointMap))
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

    // ── Center Turn 결정 (§3.1.1 fallback) ──

    private Turn resolveCenterTurn(Chat pathChat, Long centerTurnId, Map<Long, Chat> chatMap) {
        if (centerTurnId != null) {
            return turnRepository.findById(centerTurnId)
                    .orElseThrow(() -> new ProjectException(ErrorStatus.TURN_NOT_FOUND));
        }

        // Rule 1: lastTurnId 존재
        if (pathChat.getLastTurnId() != null) {
            return turnRepository.findById(pathChat.getLastTurnId()).orElse(null);
        }

        // Rule 2: branch인데 lastTurnId 없음 → branchPointTurnId 사용
        if (pathChat.getParentId() != null && pathChat.getBranchPointTurnId() != null) {
            return turnRepository.findById(pathChat.getBranchPointTurnId()).orElse(null);
        }

        // Rule 3: root인데 turn 없음 → null (빈 응답)
        return null;
    }

    // ── UP 탐색 (ancestor path, 단일 경로) ──

    private List<Turn> traceUp(Turn centerTurn, int limit, Map<Long, Chat> chatMap) {
        List<Turn> result = new ArrayList<>();
        int remaining = limit;
        Long currentChatId = centerTurn.getChat().getId();
        int currentSequence = centerTurn.getTurnSequence();

        while (remaining > 0) {
            List<Turn> before = turnRepository
                    .findByChatIdAndTurnSequenceLessThanOrderByTurnSequenceDesc(
                            currentChatId, currentSequence, PageRequest.of(0, remaining));

            result.addAll(before);
            remaining -= before.size();
            if (remaining <= 0) break;

            // 부모 chat으로 점프
            Chat chat = chatMap.get(currentChatId);
            if (chat == null || chat.getParentId() == null || chat.getBranchPointTurnId() == null) {
                break;
            }

            Turn branchPointTurn = turnRepository.findById(chat.getBranchPointTurnId()).orElse(null);
            if (branchPointTurn == null) break;

            result.add(branchPointTurn);
            remaining--;
            currentChatId = branchPointTurn.getChat().getId();
            currentSequence = branchPointTurn.getTurnSequence();
        }

        return result;
    }

    // ── DOWN 탐색 (BFS, 다중 경로) ──

    private List<Turn> traceDown(Turn centerTurn, int limit, Map<Long, List<Chat>> branchPointMap) {
        List<Turn> result = new ArrayList<>();
        Deque<long[]> queue = new ArrayDeque<>();
        int remaining = limit;

        queue.add(new long[]{centerTurn.getChat().getId(), centerTurn.getTurnSequence()});

        while (!queue.isEmpty() && remaining > 0) {
            long[] entry = queue.poll();
            long chatId = entry[0];
            int fromSeq = (int) entry[1];

            List<Turn> after = turnRepository
                    .findByChatIdAndTurnSequenceGreaterThanOrderByTurnSequenceAsc(
                            chatId, fromSeq, PageRequest.of(0, remaining));

            for (Turn turn : after) {
                if (remaining <= 0) break;
                result.add(turn);
                remaining--;

                List<Chat> children = branchPointMap.getOrDefault(turn.getId(), List.of());
                for (Chat child : children) {
                    queue.add(new long[]{child.getId(), 0});
                }
            }
        }

        return result;
    }

    // ── Frontier 계산 ──

    private GraphResDto.FrontierDto calculateFrontier(Turn centerTurn, List<Turn> upTurns,
                                                       List<Turn> downTurns, Map<Long, Chat> chatMap) {
        // UP frontier: 가장 먼 ancestor
        List<GraphResDto.FrontierPoint> upFrontier = new ArrayList<>();
        if (!upTurns.isEmpty()) {
            Turn farthest = upTurns.get(upTurns.size() - 1);
            boolean hasMore = farthest.getTurnSequence() > 1;
            if (!hasMore) {
                Chat chat = chatMap.get(farthest.getChat().getId());
                hasMore = chat != null && chat.getParentId() != null;
            }
            upFrontier.add(GraphResDto.FrontierPoint.builder()
                    .fromTurnId(farthest.getId()).hasMore(hasMore).build());
        }

        // DOWN frontier: chat별 마지막 turn
        Map<Long, Turn> lastPerChat = new LinkedHashMap<>();
        lastPerChat.put(centerTurn.getChat().getId(), centerTurn);
        for (Turn t : downTurns) {
            lastPerChat.merge(t.getChat().getId(), t,
                    (a, b) -> b.getTurnSequence() > a.getTurnSequence() ? b : a);
        }

        List<GraphResDto.FrontierPoint> downFrontier = new ArrayList<>();
        for (Turn lastTurn : lastPerChat.values()) {
            boolean hasMore = turnRepository.existsByChatIdAndTurnSequenceGreaterThan(
                    lastTurn.getChat().getId(), lastTurn.getTurnSequence());
            downFrontier.add(GraphResDto.FrontierPoint.builder()
                    .fromTurnId(lastTurn.getId()).hasMore(hasMore).build());
        }

        return GraphResDto.FrontierDto.builder().up(upFrontier).down(downFrontier).build();
    }

    // ── DTO 변환 ──

    private GraphResDto.ChatNodeDto toChatNodeDto(Chat chat, Map<Long, Chat> chatMap) {
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

    private GraphResDto.TurnNodeDto toTurnNodeDto(Turn turn, Long centerTurnId,
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

    private int calculateDepth(Chat chat, Map<Long, Chat> chatMap) {
        int depth = 0;
        Long parentId = chat.getParentId();
        while (parentId != null) {
            depth++;
            Chat parent = chatMap.get(parentId);
            if (parent == null) break;
            parentId = parent.getParentId();
        }
        return depth;
    }

    private GraphResDto.GraphResult buildEmptyResult(Long rootChatId, List<Chat> allChats,
                                                      Map<Long, Chat> chatMap) {
        return GraphResDto.GraphResult.builder()
                .rootChatId(rootChatId)
                .center(null)
                .chats(allChats.stream().map(c -> toChatNodeDto(c, chatMap)).toList())
                .turns(List.of())
                .frontier(GraphResDto.FrontierDto.builder().up(List.of()).down(List.of()).build())
                .build();
    }

    private void validateOwner(Chat chat, Long memberId) {
        if (!chat.getMember().getId().equals(memberId)) {
            throw new ProjectException(ErrorStatus.FORBIDDEN);
        }
    }
}
