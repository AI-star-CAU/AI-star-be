package com.aistar.backend.global.security;

import com.aistar.backend.domain.member.entity.Member;
import com.aistar.backend.domain.member.repository.MemberRepository;
import com.aistar.backend.global.apiPayload.code.ErrorStatus;
import com.aistar.backend.global.apiPayload.exception.ProjectException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final MemberRepository memberRepository;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        Member member = memberRepository.findByEmailAndDeletedAtIsNull(email)
                .orElseThrow(() -> new ProjectException(ErrorStatus.MEMBER_NOT_FOUND));
        return new CustomUserDetails(member);
    }

    public UserDetails loadUserById(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .filter(m -> m.getDeletedAt() == null)
                .orElseThrow(() -> new ProjectException(ErrorStatus.MEMBER_NOT_FOUND));
        return new CustomUserDetails(member);
    }
}
