package com.aistar.backend.domain.chat.controller;

import com.aistar.backend.domain.chat.dto.ExplorerResDto;
import com.aistar.backend.domain.chat.enums.ExplorerSort;
import com.aistar.backend.domain.chat.service.ExplorerService;
import com.aistar.backend.global.apiPayload.ApiResponse;
import com.aistar.backend.global.apiPayload.code.ErrorStatus;
import com.aistar.backend.global.apiPayload.code.SuccessStatus;
import com.aistar.backend.global.apiPayload.exception.ProjectException;
import com.aistar.backend.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Explorer", description = "대화 탐색기 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/chats/explorer")
public class ExplorerController {

    private final ExplorerService explorerService;

    @Operation(summary = "탐색기 트리 조회")
    @GetMapping
    public ApiResponse<ExplorerResDto.ExplorerPage> getExplorerPage(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam(defaultValue = "recent") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "false") boolean includeDeleted
    ) {
        if (size < 1 || size > 50) {
            throw new ProjectException(ErrorStatus.EXPLORER_INVALID_PARAM);
        }

        ExplorerSort explorerSort;
        try {
            explorerSort = ExplorerSort.valueOf(sort.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ProjectException(ErrorStatus.EXPLORER_INVALID_PARAM);
        }

        return ApiResponse.onSuccess(SuccessStatus.OK,
                explorerService.getExplorerPage(
                        userDetails.getMember().getId(), explorerSort, page, size, includeDeleted));
    }

    @Operation(summary = "단일 트리 새로고침")
    @GetMapping("/{rootChatId}")
    public ApiResponse<ExplorerResDto.ExplorerTreeDto> getExplorerTree(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long rootChatId,
            @RequestParam(defaultValue = "false") boolean includeDeleted
    ) {
        return ApiResponse.onSuccess(SuccessStatus.OK,
                explorerService.getExplorerTree(
                        userDetails.getMember().getId(), rootChatId, includeDeleted));
    }
}
