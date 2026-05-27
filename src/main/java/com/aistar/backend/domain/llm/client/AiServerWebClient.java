package com.aistar.backend.domain.llm.client;

import com.aistar.backend.domain.llm.dto.AiBranchTitleRequest;
import com.aistar.backend.domain.llm.dto.AiBranchTitleResponse;
import com.aistar.backend.domain.llm.dto.AiChatRequest;
import com.aistar.backend.domain.llm.dto.AiChatStreamChunk;
import com.aistar.backend.domain.llm.dto.AiCompletionResponse;
import com.aistar.backend.domain.llm.dto.AiSummaryRequest;
import com.aistar.backend.domain.llm.dto.AiSummaryResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Set;
import java.util.function.Consumer;

@Component
@RequiredArgsConstructor
public class AiServerWebClient implements AiServerClient {

    // AI-star-ai Dashboard Adapter contract. Summary and branch-title are
    // prompt wrappers over the same completion endpoint for now.
    private static final String CHAT_STREAM_PATH = "/api/v1/ai/completions/stream";
    private static final String COMPLETIONS_PATH = "/api/v1/ai/completions";
    private static final Set<Integer> AVAILABLE_STATUS_CODES = Set.of(200, 201, 202, 204, 405);
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    @Qualifier("aiServerWebClientBean")
    private final WebClient webClient;
    @Qualifier("aiServerReadTimeout")
    private final Duration readTimeout;
    private final ObjectMapper objectMapper;

    @Override
    public void streamChatCompletion(AiChatRequest request, Consumer<String> onChunk) {
        Flux<ServerSentEvent<String>> stream = webClient.post()
                .uri(CHAT_STREAM_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(request)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> AiServerException.fromStatus(
                                "AI server stream request failed",
                                response.statusCode().value(),
                                body)))
                .bodyToFlux(SSE_TYPE)
                .timeout(readTimeout)
                .onErrorMap(this::toAiServerException);

        stream.takeUntil(this::isDoneEvent).toIterable().forEach(event -> {
            if ("error".equals(event.event())) {
                throw new AiServerException("AI server stream error: " + extractErrorMessage(event.data()));
            }
            if (isDoneEvent(event)) {
                return;
            }
            String text = extractText(event.data());
            if (text != null && !text.isBlank()) {
                onChunk.accept(text);
            }
        });
    }

    private boolean isDoneEvent(ServerSentEvent<String> event) {
        return "done".equals(event.event()) || "[DONE]".equals(event.data());
    }

    @Override
    public AiCompletionResponse complete(AiChatRequest request) {
        return webClient.post()
                .uri(COMPLETIONS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> AiServerException.fromStatus(
                                "AI server completion request failed",
                                response.statusCode().value(),
                                body)))
                .bodyToMono(AiCompletionResponse.class)
                .timeout(readTimeout)
                .onErrorMap(this::toAiServerException)
                .block();
    }

    @Override
    public boolean isAvailable() {
        try {
            Integer statusCode = webClient.get()
                    .uri(COMPLETIONS_PATH)
                    .accept(MediaType.APPLICATION_JSON)
                    .exchangeToMono(response -> Mono.just(response.statusCode().value()))
                    .timeout(Duration.ofSeconds(3))
                    .onErrorReturn(-1)
                    .block();

            return statusCode != null && AVAILABLE_STATUS_CODES.contains(statusCode);
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    public AiSummaryResponse generateSummary(AiSummaryRequest request) {
        AiCompletionResponse response = complete(new AiChatRequest(
                "Summarize the following conversation in 50 Korean characters or fewer.\n\n"
                        + request.content(),
                160,
                0.3,
                null,
                null
        ));
        return new AiSummaryResponse(response != null ? response.text() : null);
    }

    @Override
    public AiBranchTitleResponse generateBranchTitle(AiBranchTitleRequest request) {
        AiCompletionResponse response = complete(new AiChatRequest(
                "Create a concise Korean title for this chat branch in 20 Korean characters or fewer.\n\n"
                        + request.context(),
                80,
                0.3,
                null,
                null
        ));
        return new AiBranchTitleResponse(response != null ? response.text() : null);
    }

    private String extractText(String rawChunk) {
        if (rawChunk == null) {
            return null;
        }

        String trimmed = rawChunk.trim();
        if (trimmed.isEmpty() || "[DONE]".equals(trimmed)) {
            return null;
        }
        if (looksLikeHtml(trimmed)) {
            throw new AiServerException("AI server returned HTML instead of SSE data");
        }

        if (trimmed.startsWith("data:")) {
            trimmed = trimmed.substring("data:".length()).trim();
        }

        if (trimmed.startsWith("{")) {
            try {
                AiChatStreamChunk chunk = objectMapper.readValue(trimmed, AiChatStreamChunk.class);
                if (Boolean.TRUE.equals(chunk.done())) {
                    return null;
                }
                if (chunk.error() != null && !chunk.error().isBlank()) {
                    throw new AiServerException("AI server returned error data: " + chunk.error());
                }
                return chunk.text() != null ? chunk.text() : chunk.content();
            } catch (JsonProcessingException ignored) {
                return trimmed;
            }
        }

        return trimmed;
    }

    private String extractErrorMessage(String data) {
        if (data == null || data.isBlank()) {
            return "empty error event";
        }

        String trimmed = data.trim();
        if (trimmed.startsWith("{")) {
            try {
                AiChatStreamChunk chunk = objectMapper.readValue(trimmed, AiChatStreamChunk.class);
                if (chunk.error() != null && !chunk.error().isBlank()) {
                    return chunk.error();
                }
            } catch (JsonProcessingException ignored) {
                return trimmed;
            }
        }

        return trimmed;
    }

    private boolean looksLikeHtml(String value) {
        String lower = value.toLowerCase();
        return lower.startsWith("<!doctype html") || lower.startsWith("<html") || lower.contains("<body");
    }

    private Throwable toAiServerException(Throwable throwable) {
        if (throwable instanceof AiServerException) {
            return throwable;
        }
        return new AiServerException("AI server call error: " + throwable.getMessage(), throwable);
    }
}
