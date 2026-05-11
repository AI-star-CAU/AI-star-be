package com.aistar.backend.domain.auth.dto;

import lombok.Builder;

public class AuthResDto {

    @Builder
    public record SignUp(
            Long memberId,
            String email,
            String name,
            String accessToken
    ) {}

    @Builder
    public record Login(
            Long memberId,
            String accessToken
    ) {}
}
