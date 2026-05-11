package com.aistar.backend.domain.member.converter;

import com.aistar.backend.domain.member.dto.MemberResDto;
import com.aistar.backend.domain.member.entity.Member;

public class MemberConverter {

    public static MemberResDto.GetInfo toGetInfo(Member member) {
        return MemberResDto.GetInfo.builder()
                .memberId(member.getId())
                .email(member.getEmail())
                .name(member.getName())
                .profileUrl(member.getProfileUrl())
                .type(member.getType())
                .createdAt(member.getCreatedAt())
                .build();
    }
}
