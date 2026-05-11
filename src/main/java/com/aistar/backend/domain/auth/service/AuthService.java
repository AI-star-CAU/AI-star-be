package com.aistar.backend.domain.auth.service;

import com.aistar.backend.domain.auth.converter.AuthConverter;
import com.aistar.backend.domain.auth.dto.AuthReqDto;
import com.aistar.backend.domain.auth.dto.AuthResDto;
import com.aistar.backend.domain.member.entity.Member;
import com.aistar.backend.domain.member.repository.MemberRepository;
import com.aistar.backend.global.apiPayload.code.ErrorStatus;
import com.aistar.backend.global.apiPayload.exception.ProjectException;
import com.aistar.backend.global.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    @Transactional
    public AuthResDto.SignUp signUp(AuthReqDto.SignUp dto) {
        // 이메일 중복 체크
        if (memberRepository.existsByEmailAndDeletedAtIsNull(dto.email())) {
            throw new ProjectException(ErrorStatus.EMAIL_ALREADY_EXISTS);
        }

        Member member = Member.builder()
                .email(dto.email())
                .password(passwordEncoder.encode(dto.password()))
                .name(dto.name())
                .build();

        memberRepository.save(member);

        String accessToken = jwtTokenProvider.createToken(member.getId(), member.getEmail());
        return AuthConverter.toSignUp(member, accessToken);
    }

    @Transactional(readOnly = true)
    public AuthResDto.Login login(AuthReqDto.Login dto) {
        // 보안상 이메일 없음/비밀번호 틀림 구분하지 않음
        Member member = memberRepository.findByEmailAndDeletedAtIsNull(dto.email())
                .orElseThrow(() -> new ProjectException(ErrorStatus.LOGIN_FAILED));

        if (!passwordEncoder.matches(dto.password(), member.getPassword())) {
            throw new ProjectException(ErrorStatus.LOGIN_FAILED);
        }

        String accessToken = jwtTokenProvider.createToken(member.getId(), member.getEmail());
        return AuthConverter.toLogin(member, accessToken);
    }
}
