package com.aistar.backend.domain.chat.controller;

import com.aistar.backend.domain.chat.dto.MessageReqDto;
import com.aistar.backend.domain.chat.service.MessageService;
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
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessage(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long chatId,
            @RequestBody @Valid MessageReqDto.Send dto
    ) {
        Long memberId = userDetails.getMember().getId();

        // 스트리밍 시작 전 검증 (실패 시 ProjectException → ApiResponse 에러)
        messageService.validateAndGetChat(memberId, chatId);

        // Turn + Messages 생성
        MessageService.TurnContext ctx = messageService.createTurnAndMessages(chatId, dto.content());

        // SSE 스트리밍 시작 (비동기)
        return messageService.streamMessage(ctx);
    }
}
