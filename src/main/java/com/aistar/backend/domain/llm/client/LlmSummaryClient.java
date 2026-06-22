package com.aistar.backend.domain.llm.client;

import com.aistar.backend.domain.llm.dto.AiSummaryRequest;
import com.aistar.backend.domain.llm.dto.AiSummaryResponse;

public interface LlmSummaryClient {
    AiSummaryResponse generateSummary(AiSummaryRequest request);
}
