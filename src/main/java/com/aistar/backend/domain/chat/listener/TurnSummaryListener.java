package com.aistar.backend.domain.chat.listener;

import com.aistar.backend.domain.chat.event.MessageCompletedEvent;
import com.aistar.backend.domain.chat.service.TurnSummaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class TurnSummaryListener {

    private final TurnSummaryService turnSummaryService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMessageCompleted(MessageCompletedEvent event) {
        if (event.fullContent() == null || event.fullContent().isBlank()) {
            return;
        }
        turnSummaryService.generateSummaryAsync(event.turnId(), event.fullContent());
    }
}
