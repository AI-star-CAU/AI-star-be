package com.aistar.backend.domain.llm.client;

public interface AiServerClient extends
        LlmStreamClient,
        LlmCompletionClient,
        LlmHealthClient,
        LlmSummaryClient,
        LlmBranchTitleClient {
}
