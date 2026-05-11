package com.aistar.backend.domain.chat.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum LlmModel {

    GPT_4O_MINI("gpt-4o-mini", LlmProvider.OPENAI),
    GEMINI_2_0_FLASH("gemini-2.0-flash", LlmProvider.GOOGLE),
    CLAUDE_3_5_SONNET("claude-3.5-sonnet", LlmProvider.ANTHROPIC),
    ;

    private final String modelId;
    private final LlmProvider provider;
}
