package com.aistar.backend.domain.chat.service;

import com.aistar.backend.domain.chat.entity.Turn;
import com.aistar.backend.domain.chat.repository.TurnRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class TurnSummaryService {

    private final TurnRepository turnRepository;
    private final TransactionTemplate transactionTemplate;

    public void generateSummaryAsync(Long turnId, String fullContent) {
        Thread.startVirtualThread(() -> {
            try {
                String summary = fullContent.length() > 50
                        ? fullContent.substring(0, 50) : fullContent;
                transactionTemplate.executeWithoutResult(status -> {
                    Turn turn = turnRepository.findById(turnId).orElseThrow();
                    turn.updateSummary(summary);
                });
            } catch (Exception e) {
                log.error("Summary 생성 실패 turnId={}", turnId, e);
            }
        });
    }
}
