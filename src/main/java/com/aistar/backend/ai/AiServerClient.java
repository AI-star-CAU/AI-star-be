package com.aistar.backend.ai;

import com.aistar.backend.ai.dto.AiBranchTitleRequest;
import com.aistar.backend.ai.dto.AiBranchTitleResponse;
import com.aistar.backend.ai.dto.AiChatRequest;
import com.aistar.backend.ai.dto.AiCompletionResponse;
import com.aistar.backend.ai.dto.AiSummaryRequest;
import com.aistar.backend.ai.dto.AiSummaryResponse;

import java.util.function.Consumer;

public interface AiServerClient {

    void streamChatCompletion(AiChatRequest request, Consumer<String> onChunk);

    AiCompletionResponse complete(AiChatRequest request);

    boolean isAvailable();

    AiSummaryResponse generateSummary(AiSummaryRequest request);

    AiBranchTitleResponse generateBranchTitle(AiBranchTitleRequest request);
}
