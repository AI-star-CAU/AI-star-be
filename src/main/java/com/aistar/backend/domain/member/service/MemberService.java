package com.aistar.backend.domain.member.service;

import com.aistar.backend.domain.member.converter.MemberConverter;
import com.aistar.backend.domain.member.dto.MemberResDto;
import com.aistar.backend.domain.member.entity.Member;
import com.aistar.backend.domain.member.repository.MemberRepository;
import com.aistar.backend.global.apiPayload.code.ErrorStatus;
import com.aistar.backend.global.apiPayload.exception.ProjectException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository memberRepository;

    @Transactional(readOnly = true)
    public MemberResDto.GetInfo getMyInfo(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .filter(m -> m.getDeletedAt() == null)
                .orElseThrow(() -> new ProjectException(ErrorStatus.MEMBER_NOT_FOUND));

        return MemberConverter.toGetInfo(member);
    }

    @Transactional
    public void deleteMember(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .filter(m -> m.getDeletedAt() == null)
                .orElseThrow(() -> new ProjectException(ErrorStatus.MEMBER_NOT_FOUND));

        member.softDelete();
    }
}
