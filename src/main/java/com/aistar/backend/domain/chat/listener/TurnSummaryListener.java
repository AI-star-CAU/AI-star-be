package com.aistar.backend.domain.chat.listener;

import com.aistar.backend.domain.chat.entity.Chat;
import com.aistar.backend.domain.chat.enums.TitleStatus;
import com.aistar.backend.domain.chat.event.MessageCompletedEvent;
import com.aistar.backend.domain.chat.repository.ChatRepository;
import com.aistar.backend.domain.chat.service.TurnSummaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class TurnSummaryListener {

    private final TurnSummaryService turnSummaryService;
    private final ChatRepository chatRepository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMessageCompleted(MessageCompletedEvent event) {
        // 요약 생성
        if (event.fullContent() != null && !event.fullContent().isBlank()) {
            turnSummaryService.generateSummaryAsync(event.turnId(), event.fullContent());
        }

        // 제목이 PENDING이면 비동기 제목 생성
        if (event.chatId() != null && event.userContent() != null && event.fullContent() != null) {
            Chat chat = chatRepository.findById(event.chatId()).orElse(null);
            if (chat != null && chat.getTitleStatus() == TitleStatus.PENDING) {
                turnSummaryService.generateTitleAsync(
                        event.chatId(), event.userContent(), event.fullContent());
            }
        }
    }
}
