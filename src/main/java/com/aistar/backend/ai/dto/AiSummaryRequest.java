package com.aistar.backend.ai.dto;

public record AiSummaryRequest(
        Long turnId,
        String content
) {
}
