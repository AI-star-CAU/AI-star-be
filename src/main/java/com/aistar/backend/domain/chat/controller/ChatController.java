package com.aistar.backend.domain.chat.controller;

import com.aistar.backend.domain.chat.dto.ChatReqDto;
import com.aistar.backend.domain.chat.dto.ChatResDto;
import com.aistar.backend.domain.chat.service.ChatService;
import com.aistar.backend.global.apiPayload.ApiResponse;
import com.aistar.backend.global.apiPayload.code.SuccessStatus;
import com.aistar.backend.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Chat", description = "대화 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/chats")
public class ChatController {

    private final ChatService chatService;

    @Operation(summary = "새 대화 시작")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ChatResDto.Detail> createChat(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody @Valid ChatReqDto.Create dto
    ) {
        return ApiResponse.onSuccess(SuccessStatus.CREATED,
                chatService.createChat(userDetails.getMember().getId(), dto));
    }

    @Operation(summary = "대화 목록 조회")
    @GetMapping
    public ApiResponse<ChatResDto.PageInfo> getChatList(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PageableDefault(size = 20, sort = "updatedAt",
                    direction = org.springframework.data.domain.Sort.Direction.DESC) Pageable pageable
    ) {
        return ApiResponse.onSuccess(SuccessStatus.OK,
                chatService.getChatList(userDetails.getMember().getId(), pageable));
    }

    @Operation(summary = "대화 메타정보 조회")
    @GetMapping("/{chatId}")
    public ApiResponse<ChatResDto.Detail> getChatDetail(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long chatId
    ) {
        return ApiResponse.onSuccess(SuccessStatus.OK,
                chatService.getChatDetail(userDetails.getMember().getId(), chatId));
    }

    @Operation(summary = "대화 삭제")
    @DeleteMapping("/{chatId}")
    public ApiResponse<Void> deleteChat(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long chatId
    ) {
        chatService.deleteChat(userDetails.getMember().getId(), chatId);
        return ApiResponse.onSuccess(SuccessStatus.OK, null);
    }
}
