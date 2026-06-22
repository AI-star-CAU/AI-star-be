package com.aistar.backend.domain.member.controller;

import com.aistar.backend.domain.member.dto.MemberResDto;
import com.aistar.backend.domain.member.service.MemberService;
import com.aistar.backend.global.apiPayload.ApiResponse;
import com.aistar.backend.global.apiPayload.code.SuccessStatus;
import com.aistar.backend.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Member", description = "회원 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/members")
public class MemberController {

    private final MemberService memberService;

    @Operation(summary = "내 정보 조회")
    @GetMapping("/me")
    public ApiResponse<MemberResDto.GetInfo> getMyInfo(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        return ApiResponse.onSuccess(SuccessStatus.OK,
                memberService.getMyInfo(userDetails.getMember().getId()));
    }

    @Operation(summary = "회원 탈퇴")
    @DeleteMapping("/me")
    public ApiResponse<Void> deleteMember(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        memberService.deleteMember(userDetails.getMember().getId());
        return ApiResponse.onSuccess(SuccessStatus.OK, null);
    }
}
