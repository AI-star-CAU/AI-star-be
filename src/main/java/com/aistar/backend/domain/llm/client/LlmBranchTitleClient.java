package com.aistar.backend.domain.llm.client;

import com.aistar.backend.domain.llm.dto.AiBranchTitleRequest;
import com.aistar.backend.domain.llm.dto.AiBranchTitleResponse;

public interface LlmBranchTitleClient {
    AiBranchTitleResponse generateBranchTitle(AiBranchTitleRequest request);
}
