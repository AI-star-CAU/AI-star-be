package com.aistar.backend.global.config;

import com.aistar.backend.domain.llm.client.LlmHealthClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class HealthController {

    private final LlmHealthClient llmHealthClient;

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @GetMapping("/health/ai-server")
    public Map<String, String> aiServerHealth() {
        if (llmHealthClient.isAvailable()) {
            return Map.of("status", "up");
        }
        return Map.of(
                "status", "down",
                "message", "ai server가 점검중입니다."
        );
    }
}
