package com.aistar.backend.domain.chat.service;

import com.aistar.backend.domain.chat.entity.Chat;
import com.aistar.backend.domain.chat.entity.Turn;
import com.aistar.backend.domain.chat.enums.TitleStatus;
import com.aistar.backend.domain.chat.repository.ChatRepository;
import com.aistar.backend.domain.chat.repository.TurnRepository;
import com.aistar.backend.domain.llm.client.LlmBranchTitleClient;
import com.aistar.backend.domain.llm.client.LlmSummaryClient;
import com.aistar.backend.domain.llm.dto.AiBranchTitleRequest;
import com.aistar.backend.domain.llm.dto.AiSummaryRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class TurnSummaryService {

    private final TurnRepository turnRepository;
    private final ChatRepository chatRepository;
    private final TransactionTemplate transactionTemplate;
    private final LlmSummaryClient llmSummaryClient;
    private final LlmBranchTitleClient llmBranchTitleClient;

    public void generateSummaryAsync(Long turnId, String fullContent) {
        Thread.startVirtualThread(() -> {
            try {
                var response = llmSummaryClient.generateSummary(
                        new AiSummaryRequest(turnId, fullContent));
                String summary = response != null && response.summary() != null
                        ? response.summary()
                        : fullContent.length() > 50 ? fullContent.substring(0, 50) : fullContent;
                transactionTemplate.executeWithoutResult(status -> {
                    Turn turn = turnRepository.findById(turnId).orElseThrow();
                    turn.updateSummary(summary);
                });
            } catch (Exception e) {
                log.error("Summary 생성 실패 turnId={}, AI 요약 실패 — fallback 사용", turnId, e);
                try {
                    String fallback = fullContent.length() > 50
                            ? fullContent.substring(0, 50) : fullContent;
                    transactionTemplate.executeWithoutResult(status -> {
                        Turn turn = turnRepository.findById(turnId).orElseThrow();
                        turn.updateSummary(fallback);
                    });
                } catch (Exception ex) {
                    log.error("Summary fallback 저장 실패 turnId={}", turnId, ex);
                }
            }
        });
    }

    public void generateTitleAsync(Long chatId, String userContent, String aiContent) {
        Thread.startVirtualThread(() -> {
            try {
                String context = userContent + "\n" + aiContent;
                var response = llmBranchTitleClient.generateBranchTitle(
                        new AiBranchTitleRequest(chatId, null, context));
                String title = response != null && response.title() != null
                        ? response.title()
                        : userContent.length() > 20 ? userContent.substring(0, 20) : userContent;
                transactionTemplate.executeWithoutResult(status -> {
                    Chat chat = chatRepository.findById(chatId).orElseThrow();
                    if (chat.getTitleStatus() == TitleStatus.PENDING) {
                        chat.updateGeneratedTitle(title);
                    }
                });
            } catch (Exception e) {
                log.error("제목 생성 실패 chatId={}, AI 호출 실패 — fallback 사용", chatId, e);
                try {
                    String fallback = userContent.length() > 20
                            ? userContent.substring(0, 20) : userContent;
                    transactionTemplate.executeWithoutResult(status -> {
                        Chat chat = chatRepository.findById(chatId).orElseThrow();
                        if (chat.getTitleStatus() == TitleStatus.PENDING) {
                            chat.updateGeneratedTitle(fallback);
                        }
                    });
                } catch (Exception ex) {
                    log.error("제목 fallback 저장 실패 chatId={}", chatId, ex);
                }
            }
        });
    }
}
