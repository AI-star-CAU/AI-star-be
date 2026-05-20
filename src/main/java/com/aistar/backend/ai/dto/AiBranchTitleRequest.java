package com.aistar.backend.ai.dto;

public record AiBranchTitleRequest(
        Long chatId,
        Long branchPointTurnId,
        String context
) {
}
