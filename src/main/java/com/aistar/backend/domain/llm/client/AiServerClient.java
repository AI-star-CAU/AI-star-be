package com.aistar.backend.domain.llm.client;

import com.aistar.backend.domain.llm.dto.AiBranchTitleRequest;
import com.aistar.backend.domain.llm.dto.AiBranchTitleResponse;
import com.aistar.backend.domain.llm.dto.AiChatRequest;
import com.aistar.backend.domain.llm.dto.AiCompletionResponse;
import com.aistar.backend.domain.llm.dto.AiSummaryRequest;
import com.aistar.backend.domain.llm.dto.AiSummaryResponse;

import java.util.function.Consumer;

public interface AiServerClient {

    void streamChatCompletion(AiChatRequest request, Consumer<String> onChunk);

    AiCompletionResponse complete(AiChatRequest request);

    boolean isAvailable();

    AiSummaryResponse generateSummary(AiSummaryRequest request);

    AiBranchTitleResponse generateBranchTitle(AiBranchTitleRequest request);
}
