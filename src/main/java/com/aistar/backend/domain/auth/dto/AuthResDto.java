package com.aistar.backend.domain.auth.dto;

import com.aistar.backend.domain.member.enums.MemberType;
import lombok.Builder;

public class AuthResDto {

    @Builder
    public record SignUp(
            Long memberId,
            String email,
            String name,
            MemberType type,
            String profileUrl,
            String accessToken
    ) {}

    @Builder
    public record Login(
            Long memberId,
            String email,
            String name,
            MemberType type,
            String profileUrl,
            String accessToken
    ) {}
}
