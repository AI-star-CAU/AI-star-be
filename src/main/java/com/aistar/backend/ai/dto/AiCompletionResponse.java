package com.aistar.backend.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AiCompletionResponse(
        String id,
        String model,
        String text,
        Usage usage
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(
            @JsonProperty("output_tokens")
            Integer outputTokens
    ) {
    }
}
