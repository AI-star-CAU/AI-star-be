package com.aistar.backend.domain.member.dto;

import com.aistar.backend.domain.member.enums.MemberType;
import lombok.Builder;

import java.time.LocalDateTime;

public class MemberResDto {

    @Builder
    public record GetInfo(
            Long memberId,
            String email,
            String name,
            String profileUrl,
            MemberType type,
            LocalDateTime createdAt
    ) {}
}
