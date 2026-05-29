package com.aistar.backend.domain.llm.client;

import com.aistar.backend.domain.llm.dto.AiChatRequest;
import com.aistar.backend.domain.llm.dto.AiCompletionResponse;

public interface LlmCompletionClient {
    AiCompletionResponse complete(AiChatRequest request);
}
