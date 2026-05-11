package com.aistar.backend.domain.llm.client;

import org.springframework.stereotype.Component;

import java.util.function.Consumer;

@Component
public class MockLlmClient implements LlmClient {

    private static final String[] CHUNKS = {
            "안녕하세요! ",
            "저는 AI 어시스턴트입니다. ",
            "질문하신 내용에 대해 ",
            "답변드리겠습니다.\n\n",
            "Spring Boot는 ",
            "Java 기반의 ",
            "웹 애플리케이션 프레임워크로, ",
            "빠르고 간편한 개발을 ",
            "지원합니다."
    };

    @Override
    public void streamCompletion(String model, String userMessage, Consumer<String> onChunk) {
        for (String chunk : CHUNKS) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            onChunk.accept(chunk);
        }
    }
}
