package com.aistar.backend.domain.usage.listener;

import com.aistar.backend.domain.chat.event.MessageCompletedEvent;
import com.aistar.backend.domain.usage.service.UsageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class UsageAccumulationListener {

    private final UsageService usageService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMessageCompleted(MessageCompletedEvent event) {
        usageService.accumulateUsage(
                event.memberId(),
                event.contextTokens(),
                event.answerToken(),
                event.compressedTurnCount()
        );
    }
}
