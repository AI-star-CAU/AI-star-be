package com.aistar.backend.domain.llm.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(AiServerProperties.class)
public class AiServerConfig {

    @Bean
    public WebClient aiServerWebClientBean(AiServerProperties properties) {
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofMillis(properties.readTimeout()))
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.connectTimeout());

        WebClient.Builder builder = WebClient.builder()
                .baseUrl(properties.baseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient));

        if (hasText(properties.apiKey())) {
            builder.defaultHeader("Authorization", "Bearer " + stripBearerPrefix(properties.apiKey()));
        }
        if (hasText(properties.cfAccessClientId())) {
            builder.defaultHeader("CF-Access-Client-Id", properties.cfAccessClientId());
        }
        if (hasText(properties.cfAccessClientSecret())) {
            builder.defaultHeader("CF-Access-Client-Secret", properties.cfAccessClientSecret());
        }

        return builder.build();
    }

    @Bean
    public Duration aiServerReadTimeout(AiServerProperties properties) {
        return Duration.ofMillis(properties.readTimeout());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String stripBearerPrefix(String value) {
        String trimmed = value.trim();
        if (trimmed.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            return trimmed.substring("Bearer ".length()).trim();
        }
        return trimmed;
    }
}
