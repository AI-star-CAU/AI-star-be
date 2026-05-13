package com.aistar.backend.domain.chat.service;

import com.aistar.backend.domain.chat.converter.TurnConverter;
import com.aistar.backend.domain.chat.dto.TurnResDto;
import com.aistar.backend.domain.chat.entity.Chat;
import com.aistar.backend.domain.chat.entity.Turn;
import com.aistar.backend.domain.chat.enums.CursorDirection;
import com.aistar.backend.domain.chat.repository.ChatRepository;
import com.aistar.backend.domain.chat.repository.TurnRepository;
import com.aistar.backend.global.apiPayload.code.ErrorStatus;
import com.aistar.backend.global.apiPayload.exception.ProjectException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TurnService {

    private final TurnRepository turnRepository;
    private final ChatRepository chatRepository;

    @Transactional(readOnly = true)
    public TurnResDto.TurnPage getTurns(Long memberId, Long chatId,
                                        Integer lastTurnSequence, int limit,
                                        CursorDirection direction) {

        Chat chat = chatRepository.findByIdAndDeletedAtIsNull(chatId)
                .orElseThrow(() -> new ProjectException(ErrorStatus.CHAT_NOT_FOUND));

        if (!chat.getMember().getId().equals(memberId)) {
            throw new ProjectException(ErrorStatus.FORBIDDEN);
        }

        // limit + 1 조회해서 hasMore 판단
        PageRequest pageRequest = PageRequest.of(0, limit + 1);
        List<Turn> turns;

        if (direction == CursorDirection.FORWARD) {
            if (lastTurnSequence == null) {
                turns = List.of();
            } else {
                turns = turnRepository.findByChatIdAndTurnSequenceGreaterThanOrderByTurnSequenceAsc(
                        chatId, lastTurnSequence, pageRequest);
            }
        } else {
            // BACKWARD
            if (lastTurnSequence == null) {
                turns = turnRepository.findByChatIdOrderByTurnSequenceDesc(chatId, pageRequest);
            } else {
                turns = turnRepository.findByChatIdAndTurnSequenceLessThanOrderByTurnSequenceDesc(
                        chatId, lastTurnSequence, pageRequest);
            }
        }

        boolean hasMore = turns.size() > limit;
        if (hasMore) {
            turns = turns.subList(0, limit);
        }

        // BACKWARD: DB에서 DESC로 가져왔으므로 ASC로 뒤집어서 응답
        if (direction == CursorDirection.BACKWARD && turns.size() > 1) {
            turns = new ArrayList<>(turns);
            Collections.reverse(turns);
        }

        List<TurnResDto.TurnItem> turnItems = turns.stream()
                .map(TurnConverter::toTurnItem)
                .toList();

        // nextTurnSequence: BACKWARD → 가장 작은 값, FORWARD → 가장 큰 값
        Integer nextTurnSequence = null;
        if (hasMore && !turns.isEmpty()) {
            if (direction == CursorDirection.BACKWARD) {
                nextTurnSequence = turns.get(0).getTurnSequence();
            } else {
                nextTurnSequence = turns.get(turns.size() - 1).getTurnSequence();
            }
        }

        return TurnResDto.TurnPage.builder()
                .turns(turnItems)
                .nextTurnSequence(nextTurnSequence)
                .hasMore(hasMore)
                .build();
    }
}
