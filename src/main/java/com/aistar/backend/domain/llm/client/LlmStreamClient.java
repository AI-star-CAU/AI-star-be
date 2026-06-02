package com.aistar.backend.domain.llm.client;

import com.aistar.backend.domain.llm.dto.AiChatRequest;

import java.util.function.Consumer;

public interface LlmStreamClient {
    void streamChatCompletion(AiChatRequest request, Consumer<String> onChunk);
}
