package com.aistar.backend.domain.chat.dto;

import com.aistar.backend.domain.chat.enums.LlmModel;
import com.aistar.backend.domain.chat.enums.LlmProvider;
import jakarta.validation.constraints.NotNull;

public class ChatReqDto {

    public record Create(
            String title,

            @NotNull
            LlmProvider llmProvider,

            @NotNull
            LlmModel llmModel
    ) {}
}
