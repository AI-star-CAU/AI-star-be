package com.aistar.backend.domain.chat.controller;

import com.aistar.backend.domain.chat.dto.MessageReqDto;
import com.aistar.backend.domain.chat.dto.MessageResDto;
import com.aistar.backend.domain.chat.service.MessageService;
import com.aistar.backend.global.apiPayload.ApiResponse;
import com.aistar.backend.global.apiPayload.code.SuccessStatus;
import com.aistar.backend.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Tag(name = "Message", description = "메시지 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/chats/{chatId}/messages")
public class MessageController {

    private final MessageService messageService;

    @Operation(summary = "메시지 송신 (SSE 스트리밍)")
    @PostMapping(produces = {MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE})
    public SseEmitter sendMessage(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long chatId,
            @RequestBody @Valid MessageReqDto.Send dto
    ) {
        Long memberId = userDetails.getMember().getId();

        // Turn + Messages 생성 (소유권 검증 포함)
        MessageService.TurnContext ctx = messageService.createTurnAndMessages(memberId, chatId, dto.content());

        // SSE 스트리밍 시작 (비동기)
        return messageService.streamMessage(ctx);
    }

    @Operation(summary = "응답 재생성 (자동 분기)")
    @PostMapping(value = "/{messageId}/regenerate", produces = {MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE})
    public SseEmitter regenerateMessage(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long chatId,
            @PathVariable Long messageId
    ) {
        Long memberId = userDetails.getMember().getId();
        MessageService.TurnContext ctx = messageService.regenerateMessage(memberId, chatId, messageId);
        return messageService.streamMessage(ctx);
    }

    @Operation(summary = "메시지 수정 (자동 분기)")
    @PatchMapping(value = "/{messageId}", produces = {MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE})
    public SseEmitter editMessage(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long chatId,
            @PathVariable Long messageId,
            @RequestBody @Valid MessageReqDto.Send dto
    ) {
        Long memberId = userDetails.getMember().getId();
        MessageService.TurnContext ctx = messageService.editMessage(memberId, chatId, messageId, dto.content());
        return messageService.streamMessage(ctx);
    }

    @Operation(summary = "메시지 스트리밍 취소")
    @PostMapping("/{messageId}/cancel")
    public ApiResponse<MessageResDto.CancelResult> cancelMessage(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long chatId,
            @PathVariable Long messageId
    ) {
        Long memberId = userDetails.getMember().getId();
        MessageResDto.CancelResult result = messageService.cancelMessage(memberId, chatId, messageId);
        return ApiResponse.onSuccess(SuccessStatus.OK, result);
    }
}
