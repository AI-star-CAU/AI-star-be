package com.aistar.backend.domain.chat.service.graph;

import com.aistar.backend.domain.chat.dto.GraphResDto;
import com.aistar.backend.domain.chat.entity.Chat;
import com.aistar.backend.domain.chat.entity.Turn;
import com.aistar.backend.domain.chat.repository.TurnRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class FrontierCalculator {

    private final TurnRepository turnRepository;

    public GraphResDto.FrontierDto calculateForGraph(Turn centerTurn, List<Turn> upTurns,
                                                     List<Turn> downTurns, Map<Long, Chat> chatMap) {
        List<GraphResDto.FrontierPoint> upFrontier = calculateUpFrontier(upTurns, chatMap);
        List<GraphResDto.FrontierPoint> downFrontier = calculateDownFrontierWithCenter(centerTurn, downTurns);
        return GraphResDto.FrontierDto.builder().up(upFrontier).down(downFrontier).build();
    }

    public GraphResDto.FrontierDto calculateForExpandUp(List<Turn> turns, Map<Long, Chat> chatMap) {
        return GraphResDto.FrontierDto.builder()
                .up(calculateUpFrontier(turns, chatMap))
                .down(List.of())
                .build();
    }

    public GraphResDto.FrontierDto calculateForExpandDown(List<Turn> turns) {
        return GraphResDto.FrontierDto.builder()
                .up(List.of())
                .down(calculateDownFrontier(turns))
                .build();
    }

    private List<GraphResDto.FrontierPoint> calculateUpFrontier(List<Turn> turns, Map<Long, Chat> chatMap) {
        List<GraphResDto.FrontierPoint> upFrontier = new ArrayList<>();
        if (!turns.isEmpty()) {
            Turn farthest = turns.get(turns.size() - 1);
            boolean hasMore = farthest.getTurnSequence() > 1;
            if (!hasMore) {
                Chat chat = chatMap.get(farthest.getChat().getId());
                hasMore = chat != null && chat.getParentId() != null;
            }
            upFrontier.add(GraphResDto.FrontierPoint.builder()
                    .fromTurnId(farthest.getId()).hasMore(hasMore).build());
        }
        return upFrontier;
    }

    private List<GraphResDto.FrontierPoint> calculateDownFrontierWithCenter(Turn centerTurn, List<Turn> downTurns) {
        Map<Long, Turn> lastPerChat = new LinkedHashMap<>();
        lastPerChat.put(centerTurn.getChat().getId(), centerTurn);
        for (Turn t : downTurns) {
            lastPerChat.merge(t.getChat().getId(), t,
                    (a, b) -> b.getTurnSequence() > a.getTurnSequence() ? b : a);
        }
        return buildDownFrontier(lastPerChat);
    }

    private List<GraphResDto.FrontierPoint> calculateDownFrontier(List<Turn> turns) {
        Map<Long, Turn> lastPerChat = new LinkedHashMap<>();
        for (Turn t : turns) {
            lastPerChat.merge(t.getChat().getId(), t,
                    (a, b) -> b.getTurnSequence() > a.getTurnSequence() ? b : a);
        }
        return buildDownFrontier(lastPerChat);
    }

    private List<GraphResDto.FrontierPoint> buildDownFrontier(Map<Long, Turn> lastPerChat) {
        List<GraphResDto.FrontierPoint> downFrontier = new ArrayList<>();
        for (Turn lastTurn : lastPerChat.values()) {
            boolean hasMore = turnRepository.existsByChatIdAndTurnSequenceGreaterThan(
                    lastTurn.getChat().getId(), lastTurn.getTurnSequence());
            downFrontier.add(GraphResDto.FrontierPoint.builder()
                    .fromTurnId(lastTurn.getId()).hasMore(hasMore).build());
        }
        return downFrontier;
    }
}
