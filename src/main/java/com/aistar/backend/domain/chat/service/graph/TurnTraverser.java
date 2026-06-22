package com.aistar.backend.domain.chat.service.graph;

import com.aistar.backend.domain.chat.entity.Chat;
import com.aistar.backend.domain.chat.entity.Turn;
import com.aistar.backend.domain.chat.repository.TurnRepository;
import com.aistar.backend.global.apiPayload.code.ErrorStatus;
import com.aistar.backend.global.apiPayload.exception.ProjectException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class TurnTraverser {

    private final TurnRepository turnRepository;

    public Turn resolveCenterTurn(Chat pathChat, Long centerTurnId) {
        if (centerTurnId != null) {
            return turnRepository.findById(centerTurnId)
                    .orElseThrow(() -> new ProjectException(ErrorStatus.TURN_NOT_FOUND));
        }

        if (pathChat.getLastTurnId() != null) {
            return turnRepository.findById(pathChat.getLastTurnId()).orElse(null);
        }

        if (pathChat.getParentId() != null && pathChat.getBranchPointTurnId() != null) {
            return turnRepository.findById(pathChat.getBranchPointTurnId()).orElse(null);
        }

        return null;
    }

    public List<Turn> traceUp(Turn centerTurn, int limit, Map<Long, Chat> chatMap) {
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
            if (remaining <= 0) {
                break;
            }

            Chat chat = chatMap.get(currentChatId);
            if (chat == null || chat.getParentId() == null || chat.getBranchPointTurnId() == null) {
                break;
            }

            Turn branchPointTurn = turnRepository.findById(chat.getBranchPointTurnId()).orElse(null);
            if (branchPointTurn == null) {
                break;
            }

            result.add(branchPointTurn);
            remaining--;
            currentChatId = branchPointTurn.getChat().getId();
            currentSequence = branchPointTurn.getTurnSequence();
        }

        return result;
    }

    public List<Turn> traceDown(Turn centerTurn, int limit, Map<Long, List<Chat>> branchPointMap) {
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
                if (remaining <= 0) {
                    break;
                }
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
}
