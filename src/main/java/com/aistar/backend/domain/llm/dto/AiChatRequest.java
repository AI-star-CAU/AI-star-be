package com.aistar.backend.domain.llm.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiChatRequest(
        String prompt,
        @JsonProperty("max_new_tokens")
        Integer maxNewTokens,
        Double temperature,
        String model,
        Object messages
) {
}
