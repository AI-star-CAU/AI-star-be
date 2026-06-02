package com.aistar.backend.domain.chat.enums;

/**
 * @deprecated LLM enum은 domain.llm.enums로 이동했다.
 * 신규 코드에서는 com.aistar.backend.domain.llm.enums.LlmProvider를 사용한다.
 */
@Deprecated
public enum LlmProvider {
    LOCAL,
    OPENAI,
    GOOGLE,
    ANTHROPIC
}
