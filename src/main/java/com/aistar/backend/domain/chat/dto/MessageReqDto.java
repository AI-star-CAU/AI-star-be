package com.aistar.backend.domain.chat.dto;

import jakarta.validation.constraints.NotBlank;

public class MessageReqDto {

    public record Send(
            @NotBlank
            String content
    ) {}
}
