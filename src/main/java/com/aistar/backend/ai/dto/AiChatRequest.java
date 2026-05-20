package com.aistar.backend.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiChatRequest(
        String prompt,
        Integer maxNewTokens,
        Double temperature,
        String model,
        Object messages
) {
}
