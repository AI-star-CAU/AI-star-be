package com.aistar.backend.domain.chat.controller;

import com.aistar.backend.domain.chat.dto.GraphResDto;
import com.aistar.backend.domain.chat.service.GraphService;
import com.aistar.backend.global.apiPayload.ApiResponse;
import com.aistar.backend.global.apiPayload.code.SuccessStatus;
import com.aistar.backend.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Graph", description = "그래프 시각화 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/chats/{chatId}/graph")
public class GraphController {

    private final GraphService graphService;

    @Operation(summary = "그래프 조회")
    @GetMapping
    public ApiResponse<GraphResDto.GraphResult> getGraph(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long chatId,
            @RequestParam(required = false) Long centerTurnId,
            @RequestParam(defaultValue = "30") int up,
            @RequestParam(defaultValue = "30") int down,
            @RequestParam(defaultValue = "false") boolean includeDeleted
    ) {
        return ApiResponse.onSuccess(SuccessStatus.OK,
                graphService.getGraph(userDetails.getMember().getId(), chatId,
                        centerTurnId, up, down, includeDeleted));
    }

    @Operation(summary = "그래프 윈도우 확장")
    @GetMapping("/expand")
    public ApiResponse<GraphResDto.ExpandResult> expandWindow(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long chatId,
            @RequestParam Long fromTurnId,
            @RequestParam String direction,
            @RequestParam(defaultValue = "30") int limit,
            @RequestParam(defaultValue = "false") boolean includeDeleted
    ) {
        return ApiResponse.onSuccess(SuccessStatus.OK,
                graphService.expandWindow(userDetails.getMember().getId(), chatId,
                        fromTurnId, direction, limit, includeDeleted));
    }
}
