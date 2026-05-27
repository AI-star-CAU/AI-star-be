package com.aistar.backend.domain.chat.dto;

import com.aistar.backend.domain.chat.enums.LlmModel;
import com.aistar.backend.domain.chat.enums.LlmProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class ChatReqDto {

    public record Create(
            String title,
            LlmProvider llmProvider,
            LlmModel llmModel
    ) {
        public LlmProvider llmProviderOrDefault() {
            return llmProvider != null ? llmProvider : LlmProvider.LOCAL;
        }

        public LlmModel llmModelOrDefault() {
            return llmModel != null ? llmModel : LlmModel.LOCAL_DEFAULT;
        }
    }

    public record BranchCreate(
            @NotNull(message = "분기점 turn ID는 필수입니다.")
            Long branchPointTurnId,

            String title
    ) {}

    public record UpdateTitle(
            @NotBlank(message = "제목은 필수입니다.")
            @Size(min = 1, max = 100, message = "제목은 1~100자여야 합니다.")
            String title
    ) {}
}
