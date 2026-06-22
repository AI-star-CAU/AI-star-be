package com.aistar.backend.domain.llm.dto;

public record AiBranchTitleRequest(
        Long chatId,
        Long branchPointTurnId,
        String context
) {
}
