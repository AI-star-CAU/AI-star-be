package com.aistar.backend.domain.llm.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum LlmModel {

    GPT_4O_MINI("gpt-4o-mini", LlmProvider.OPENAI, 128_000),
    GEMINI_2_0_FLASH("gemini-2.0-flash", LlmProvider.GOOGLE, 1_048_576),
    CLAUDE_3_5_SONNET("claude-3.5-sonnet", LlmProvider.ANTHROPIC, 200_000),
    ;

    private final String modelId;
    private final LlmProvider provider;
    private final int contextWindow;

    @JsonValue
    public String getModelId() {
        return modelId;
    }

    @JsonCreator
    public static LlmModel fromModelId(String modelId) {
        for (LlmModel model : values()) {
            if (model.modelId.equals(modelId)) {
                return model;
            }
        }
        throw new IllegalArgumentException("Unknown model: " + modelId);
    }
}
