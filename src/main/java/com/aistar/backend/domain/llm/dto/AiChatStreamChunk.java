package com.aistar.backend.domain.llm.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AiChatStreamChunk(
        String text,
        String content,
        String error,
        Boolean done
) {
}
