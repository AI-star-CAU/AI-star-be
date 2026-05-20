package com.aistar.backend.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai.server")
public record AiServerProperties(
        String baseUrl,
        int connectTimeout,
        int readTimeout,
        String apiKey,
        String cfAccessClientId,
        String cfAccessClientSecret
) {
}
