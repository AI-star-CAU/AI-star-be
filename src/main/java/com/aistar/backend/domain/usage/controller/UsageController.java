package com.aistar.backend.domain.usage.controller;

import com.aistar.backend.domain.usage.dto.UsageResDto;
import com.aistar.backend.domain.usage.service.UsageService;
import com.aistar.backend.global.apiPayload.ApiResponse;
import com.aistar.backend.global.apiPayload.code.SuccessStatus;
import com.aistar.backend.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Usage", description = "토큰 사용량 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/usage")
public class UsageController {

    private final UsageService usageService;

    @Operation(summary = "내 토큰 사용량 조회")
    @GetMapping("/me")
    public ApiResponse<UsageResDto.UsageInfo> getMyUsage(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        return ApiResponse.onSuccess(SuccessStatus.OK,
                usageService.getMyUsage(userDetails.getMember().getId()));
    }
}
