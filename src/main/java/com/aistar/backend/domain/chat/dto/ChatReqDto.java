package com.aistar.backend.domain.chat.dto;

import com.aistar.backend.domain.chat.enums.LlmModel;
import com.aistar.backend.domain.chat.enums.LlmProvider;
import jakarta.validation.constraints.NotNull;

public class ChatReqDto {

    public record Create(
            String title,

            @NotNull(message = "LLM 제공자는 필수입니다.")
            LlmProvider llmProvider,

            @NotNull(message = "LLM 모델은 필수입니다.")
            LlmModel llmModel
    ) {}
}
