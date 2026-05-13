package com.aistar.backend.domain.auth.converter;

import com.aistar.backend.domain.auth.dto.AuthResDto;
import com.aistar.backend.domain.member.entity.Member;

public class AuthConverter {

    public static AuthResDto.SignUp toSignUp(Member member, String accessToken) {
        return AuthResDto.SignUp.builder()
                .memberId(member.getId())
                .email(member.getEmail())
                .name(member.getName())
                .type(member.getType())
                .profileUrl(member.getProfileUrl())
                .accessToken(accessToken)
                .build();
    }

    public static AuthResDto.Login toLogin(Member member, String accessToken) {
        return AuthResDto.Login.builder()
                .memberId(member.getId())
                .email(member.getEmail())
                .name(member.getName())
                .type(member.getType())
                .profileUrl(member.getProfileUrl())
                .accessToken(accessToken)
                .build();
    }
}
