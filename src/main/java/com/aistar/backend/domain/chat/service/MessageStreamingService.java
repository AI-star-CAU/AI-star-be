package com.aistar.backend.domain.chat.service;

import com.aistar.backend.domain.chat.dto.MessageResDto;
import com.aistar.backend.domain.chat.entity.Chat;
import com.aistar.backend.domain.chat.entity.Message;
import com.aistar.backend.domain.chat.event.MessageCompletedEvent;
import com.aistar.backend.domain.chat.enums.MessageStatus;
import com.aistar.backend.domain.chat.repository.ChatRepository;
import com.aistar.backend.domain.chat.repository.MessageRepository;
import com.aistar.backend.domain.llm.client.AiServerException;
import com.aistar.backend.domain.llm.client.LlmStreamClient;
import com.aistar.backend.domain.llm.dto.AiChatRequest;
import com.aistar.backend.global.apiPayload.code.ErrorStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageStreamingService {

    private static final long SSE_TIMEOUT = 60_000L * 5;

    private final ContextAssembler contextAssembler;
    private final LlmStreamClient llmStreamClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final MessageRepository messageRepository;
    private final ChatRepository chatRepository;
    private final ChatService chatService;
    private final StreamingRegistry streamingRegistry;
    private final ApplicationEventPublisher applicationEventPublisher;

    public SseEmitter streamMessage(MessageService.TurnContext ctx) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        Long aiMessageId = ctx.aiMessage().getId();

        StreamingRegistry.StreamingContext streamCtx = streamingRegistry.register(aiMessageId);

        Thread.startVirtualThread(() -> {
            final ContextAssembler.ContextResult[] ctxHolder = {null};
            try {
                if (ctx.branchCreated() != null) {
                    sendEvent(emitter, "branch_created", ctx.branchCreated());
                }

                sendEvent(emitter, "turn_started", MessageResDto.TurnStarted.builder()
                        .chatId(ctx.chat().getId())
                        .turnId(ctx.turn().getId())
                        .userMessageId(ctx.userMessage().getId())
                        .aiMessageId(aiMessageId)
                        .build());

                ctxHolder[0] = contextAssembler.buildContext(
                        ctx.chat(), ctx.turn(), ctx.userMessage().getContent());

                llmStreamClient.streamChatCompletion(
                        new AiChatRequest(
                                ctx.userMessage().getContent(),
                                1024,
                                0.7,
                                null,
                                ctxHolder[0].messages()
                        ),
                        chunk -> {
                            if (streamCtx.canceled().get()) {
                                throw new CancelException();
                            }
                            streamCtx.contentBuffer().append(chunk);
                            try {
                                sendEvent(emitter, "chunk", MessageResDto.Chunk.builder()
                                        .text(chunk).build());
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }
                );

                String fullContent = streamCtx.contentBuffer().toString();
                int answerToken = fullContent.split("\\s+").length;
                Long memberId = ctx.memberId();
                Long chatId = ctx.chat().getId();
                Long turnId = ctx.turn().getId();

                transactionTemplate.executeWithoutResult(status -> {
                    Message message = messageRepository.findById(aiMessageId).orElseThrow();
                    if (message.getStatus() == MessageStatus.STREAMING) {
                        message.updateContent(fullContent);
                        message.updateStatus(MessageStatus.COMPLETED);
                        message.updateAnswerToken(answerToken);
                        message.updatePromptToken(ctxHolder[0].contextTokens());
                    }
                    Chat chat = chatRepository.findById(chatId).orElseThrow();
                    chat.updateLastTurnId(turnId);
                    chat.touchUpdatedAt();
                    chatService.touchAncestorChain(chatId);
                    applicationEventPublisher.publishEvent(new MessageCompletedEvent(
                            memberId,
                            chatId,
                            turnId,
                            ctxHolder[0].contextTokens(), answerToken,
                            ctxHolder[0].compressedTurnCount(),
                            fullContent,
                            ctx.userMessage().getContent()
                    ));
                });

                sendEvent(emitter, "turn_completed", MessageResDto.TurnCompleted.builder()
                        .turnId(ctx.turn().getId())
                        .aiMessageId(aiMessageId)
                        .answerToken(answerToken)
                        .summaryStatus("PENDING")
                        .contextTokens(ctxHolder[0].contextTokens())
                        .compressionApplied(ctxHolder[0].compressionApplied())
                        .compressedTurnCount(ctxHolder[0].compressedTurnCount())
                        .build());

                sendDoneAndComplete(emitter);
            } catch (CancelException e) {
                String partialContent = streamCtx.contentBuffer().toString();
                Integer partialToken = partialContent.isEmpty() ? null : partialContent.split("\\s+").length;
                Long cancelMemberId = ctx.memberId();
                Long cancelChatId = ctx.chat().getId();
                Long cancelTurnId = ctx.turn().getId();

                transactionTemplate.executeWithoutResult(status -> {
                    Chat chat = chatRepository.findById(cancelChatId).orElseThrow();
                    chat.touchUpdatedAt();
                    if (ctxHolder[0] != null) {
                        applicationEventPublisher.publishEvent(new MessageCompletedEvent(
                                cancelMemberId,
                                cancelChatId,
                                cancelTurnId,
                                ctxHolder[0].contextTokens(),
                                partialToken != null ? partialToken : 0,
                                ctxHolder[0].compressedTurnCount(),
                                null,
                                null
                        ));
                    }
                });

                try {
                    sendEvent(emitter, "cancelled", MessageResDto.Cancelled.builder()
                            .aiMessageId(aiMessageId)
                            .status(MessageStatus.CANCELED)
                            .content(partialContent.isEmpty() ? null : partialContent)
                            .answerToken(partialToken)
                            .build());
                } catch (IOException ignored) {
                }
                sendDoneAndComplete(emitter);
            } catch (Exception e) {
                log.error("SSE 스트리밍 실패", e);
                Long errorMemberId = ctx.memberId();
                Long errorChatId = ctx.chat().getId();
                Long errorTurnId = ctx.turn().getId();
                try {
                    transactionTemplate.executeWithoutResult(status -> {
                        Message message = messageRepository.findById(aiMessageId).orElseThrow();
                        boolean shouldMarkFailed = message.getStatus() == MessageStatus.STREAMING;
                        if (shouldMarkFailed) {
                            message.updateStatus(MessageStatus.FAILED);
                        }
                        Chat chat = chatRepository.findById(errorChatId).orElseThrow();
                        chat.touchUpdatedAt();
                        if (shouldMarkFailed && ctxHolder[0] != null) {
                            applicationEventPublisher.publishEvent(new MessageCompletedEvent(
                                    errorMemberId,
                                    errorChatId,
                                    errorTurnId,
                                    ctxHolder[0].contextTokens(),
                                    0,
                                    ctxHolder[0].compressedTurnCount(),
                                    null,
                                    null
                            ));
                        }
                    });
                    ErrorStatus errorStatus = resolveAiErrorStatus(e);
                    sendEvent(emitter, "error", MessageResDto.SseError.builder()
                            .code(errorStatus.getCode())
                            .message(errorStatus.getMessage())
                            .retryable(errorStatus == ErrorStatus.AI_SERVER_UNAVAILABLE)
                            .build());
                    sendDoneAndComplete(emitter);
                } catch (IOException ignored) {
                    emitter.completeWithError(e);
                }
            } finally {
                streamingRegistry.remove(aiMessageId);
            }
        });

        return emitter;
    }

    private void sendDoneAndComplete(SseEmitter emitter) {
        try {
            sendEvent(emitter, "done", new MessageResDto.Done());
        } catch (IOException ignored) {
        }
        emitter.complete();
    }

    private void sendEvent(SseEmitter emitter, String eventName, Object data) throws IOException {
        emitter.send(SseEmitter.event()
                .name(eventName)
                .data(objectMapper.writeValueAsString(data)));
    }

    private ErrorStatus resolveAiErrorStatus(Exception e) {
        if (isAiServerUnavailable(e)) {
            return ErrorStatus.AI_SERVER_UNAVAILABLE;
        }
        return ErrorStatus.LLM_CALL_FAILED;
    }

    private boolean isAiServerUnavailable(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof AiServerException aiServerException && aiServerException.isLikelyUnavailable()) {
                return true;
            }
            if (current instanceof TimeoutException
                    || current instanceof SocketTimeoutException
                    || current instanceof ConnectException
                    || current instanceof UnknownHostException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static class CancelException extends RuntimeException {}
}
