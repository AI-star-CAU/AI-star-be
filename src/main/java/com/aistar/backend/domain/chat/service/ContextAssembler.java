package com.aistar.backend.domain.chat.service;

import com.aistar.backend.domain.chat.entity.Chat;
import com.aistar.backend.domain.chat.entity.Message;
import com.aistar.backend.domain.chat.entity.Turn;
import com.aistar.backend.domain.chat.enums.SenderType;
import com.aistar.backend.domain.chat.repository.ChatRepository;
import com.aistar.backend.domain.chat.repository.TurnRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
public class ContextAssembler {

    private final ChatRepository chatRepository;
    private final TurnRepository turnRepository;

    /**
     * 명세서 §3.1 — 분기별 맥락 조립.
     * ancestor chain의 turn을 시간순으로 펼치고, 새 user 메시지를 끝에 추가한다.
     */
    @Transactional(readOnly = true)
    public List<Map<String, String>> buildContext(Chat targetChat, Turn currentTurn, String newUserContent) {
        List<Chat> chain = buildAncestorChain(targetChat);

        List<Map<String, String>> messages = new ArrayList<>();

        for (int i = 0; i < chain.size(); i++) {
            Chat chat = chain.get(i);
            List<Turn> turns;

            if (i < chain.size() - 1) {
                // ancestor: 다음 child의 branchPointTurnId 기준 turn_sequence 이하만 포함
                Chat nextChild = chain.get(i + 1);
                Turn bpTurn = turnRepository.findById(nextChild.getBranchPointTurnId()).orElseThrow();
                turns = turnRepository.findByChatIdAndTurnSequenceLteWithMessages(
                        chat.getId(), bpTurn.getTurnSequence());
            } else {
                // target: 현재 turn 이전의 모든 turn (현재 turn의 user 메시지는 아래에서 별도 추가)
                int maxSeq = currentTurn.getTurnSequence() - 1;
                turns = maxSeq >= 1
                        ? turnRepository.findByChatIdAndTurnSequenceLteWithMessages(chat.getId(), maxSeq)
                        : List.of();
            }

            for (Turn turn : turns) {
                for (Message msg : turn.getMessages()) {
                    if (msg.getContent() != null) {
                        String role = msg.getSenderType() == SenderType.USER ? "user" : "assistant";
                        messages.add(Map.of("role", role, "content", msg.getContent()));
                    }
                }
            }
        }

        // 새 user 메시지 추가
        messages.add(Map.of("role", "user", "content", newUserContent));

        return messages;
    }

    /**
     * target → parent → ... → root 순으로 올라간 뒤 reverse하여
     * [root, ..., target] chain을 반환한다.
     */
    private List<Chat> buildAncestorChain(Chat targetChat) {
        List<Chat> chain = new ArrayList<>();
        chain.add(targetChat);

        Long parentId = targetChat.getParentId();
        while (parentId != null) {
            Chat parent = chatRepository.findById(parentId).orElse(null);
            if (parent == null) break;
            chain.add(parent);
            parentId = parent.getParentId();
        }

        Collections.reverse(chain);
        return chain;
    }
}
