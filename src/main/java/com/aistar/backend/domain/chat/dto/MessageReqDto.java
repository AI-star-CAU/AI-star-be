package com.aistar.backend.domain.chat.dto;

import jakarta.validation.constraints.NotBlank;

public class MessageReqDto {

    public record Send(
            @NotBlank(message = "메시지 내용은 필수입니다.")
            String content
    ) {}
}
