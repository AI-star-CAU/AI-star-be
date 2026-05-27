package com.aistar.backend.domain.llm.dto;

public record AiSummaryRequest(
        Long turnId,
        String content
) {
}
