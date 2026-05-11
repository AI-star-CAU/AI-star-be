package com.aistar.backend.domain.llm.client;

import java.util.function.Consumer;

public interface LlmClient {

    /**
     * LLM 스트리밍 호출.
     * chunk가 생성될 때마다 onChunk 콜백 호출.
     * 완료 시 리턴.
     */
    void streamCompletion(String model, String userMessage, Consumer<String> onChunk);
}
