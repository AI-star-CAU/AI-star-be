package com.aistar.backend.domain.chat.service;

import com.aistar.backend.domain.chat.entity.Chat;
import com.aistar.backend.domain.chat.entity.Message;
import com.aistar.backend.domain.chat.entity.Turn;
import com.aistar.backend.domain.llm.enums.LlmModel;
import com.aistar.backend.domain.chat.enums.SenderType;
import com.aistar.backend.domain.chat.repository.ChatRepository;
import com.aistar.backend.domain.chat.repository.TurnRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContextAssembler {

    private static final double COMPRESSION_RATIO = 0.8;
    private static final int PROTECTED_TURN_COUNT = 2;
    private static final int TRUNCATION_CHAR_LIMIT = 200;

    private final ChatRepository chatRepository;
    private final TurnRepository turnRepository;

    public record ContextResult(
            List<Map<String, String>> messages,
            int contextTokens,
            boolean compressionApplied,
            int compressedTurnCount
    ) {}

    /**
     * 명세서 §3.1 맥락 조립 + §3.2 압축.
     * ancestor chain의 turn을 시간순으로 펼치고, 임계 초과 시 오래된 turn부터 압축한다.
     */
    @Transactional(readOnly = true)
    public ContextResult buildContext(Chat targetChat, Turn currentTurn,
                                      String newUserContent, LlmModel model) {
        List<Chat> chain = buildAncestorChain(targetChat);

        // 1. 턴 수집 (Turn entity 유지 — 압축 시 summary 참조 필요)
        List<Turn> orderedTurns = collectTurns(chain, currentTurn);

        // 2. 턴별 메시지 그룹 빌드
        List<List<Map<String, String>>> turnMessageGroups = new ArrayList<>();
        for (Turn turn : orderedTurns) {
            List<Map<String, String>> msgs = new ArrayList<>();
            for (Message msg : turn.getMessages()) {
                if (msg.getContent() != null) {
                    String role = msg.getSenderType() == SenderType.USER ? "user" : "assistant";
                    msgs.add(Map.of("role", role, "content", msg.getContent()));
                }
            }
            turnMessageGroups.add(msgs);
        }

        // 3. 압축 (§3.2)
        int threshold = (int) (model.getContextWindow() * COMPRESSION_RATIO);
        int totalTokens = estimateTokensForGroups(turnMessageGroups)
                + estimateTokens(newUserContent);
        boolean compressionApplied = false;
        int compressedTurnCount = 0;

        if (totalTokens > threshold) {
            int compressibleCount = Math.max(0, orderedTurns.size() - PROTECTED_TURN_COUNT);

            for (int i = 0; i < compressibleCount && totalTokens > threshold; i++) {
                Turn turn = orderedTurns.get(i);
                List<Map<String, String>> original = turnMessageGroups.get(i);
                int oldTokens = estimateTokensForMsgList(original);

                List<Map<String, String>> replacement;
                if (turn.getSummary() != null) {
                    replacement = List.of(
                            Map.of("role", "user", "content", "이전 대화 요약: " + turn.getSummary()));
                } else {
                    replacement = truncateMessages(original);
                }

                int newTokens = estimateTokensForMsgList(replacement);
                totalTokens -= (oldTokens - newTokens);
                turnMessageGroups.set(i, new ArrayList<>(replacement));
                compressionApplied = true;
                compressedTurnCount++;
            }

            if (totalTokens > threshold) {
                log.warn("context_overflow_after_compression: contextTokens={}, threshold={}",
                        totalTokens, threshold);
            }
        }

        // 4. flatten + 새 user 메시지 추가
        List<Map<String, String>> finalMessages = new ArrayList<>();
        for (var group : turnMessageGroups) {
            finalMessages.addAll(group);
        }
        finalMessages.add(Map.of("role", "user", "content", newUserContent));

        int contextTokens = estimateTokensForMsgList(finalMessages);

        return new ContextResult(finalMessages, contextTokens, compressionApplied, compressedTurnCount);
    }

    private List<Turn> collectTurns(List<Chat> chain, Turn currentTurn) {
        List<Turn> orderedTurns = new ArrayList<>();
        for (int i = 0; i < chain.size(); i++) {
            Chat chat = chain.get(i);
            List<Turn> turns;
            if (i < chain.size() - 1) {
                Chat nextChild = chain.get(i + 1);
                Turn bpTurn = turnRepository.findById(nextChild.getBranchPointTurnId()).orElseThrow();
                turns = turnRepository.findByChatIdAndTurnSequenceLteWithMessages(
                        chat.getId(), bpTurn.getTurnSequence());
            } else {
                int maxSeq = currentTurn.getTurnSequence() - 1;
                turns = maxSeq >= 1
                        ? turnRepository.findByChatIdAndTurnSequenceLteWithMessages(chat.getId(), maxSeq)
                        : List.of();
            }
            orderedTurns.addAll(turns);
        }
        return orderedTurns;
    }

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

    private List<Map<String, String>> truncateMessages(List<Map<String, String>> messages) {
        List<Map<String, String>> result = new ArrayList<>();
        for (Map<String, String> msg : messages) {
            String content = msg.get("content");
            if (content != null && content.length() > TRUNCATION_CHAR_LIMIT) {
                content = content.substring(0, TRUNCATION_CHAR_LIMIT) + "...[일부 생략]";
            }
            result.add(Map.of("role", msg.get("role"), "content", content != null ? content : ""));
        }
        return result;
    }

    private int estimateTokens(String text) {
        return text == null ? 0 : Math.max(1, text.length() / 4);
    }

    private int estimateTokensForMsgList(List<Map<String, String>> messages) {
        int total = 0;
        for (var msg : messages) {
            total += estimateTokens(msg.get("content"));
        }
        return total;
    }

    private int estimateTokensForGroups(List<List<Map<String, String>>> groups) {
        int total = 0;
        for (var group : groups) {
            total += estimateTokensForMsgList(group);
        }
        return total;
    }
}

