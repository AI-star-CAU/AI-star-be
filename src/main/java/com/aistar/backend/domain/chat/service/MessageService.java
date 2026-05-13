package com.aistar.backend.domain.chat.service;

import com.aistar.backend.domain.chat.dto.MessageResDto;
import com.aistar.backend.domain.chat.entity.Chat;
import com.aistar.backend.domain.chat.entity.Message;
import com.aistar.backend.domain.chat.entity.Turn;
import com.aistar.backend.domain.chat.enums.MessageStatus;
import com.aistar.backend.domain.chat.enums.SenderType;
import com.aistar.backend.domain.chat.repository.ChatRepository;
import com.aistar.backend.domain.chat.repository.MessageRepository;
import com.aistar.backend.domain.chat.repository.TurnRepository;
import com.aistar.backend.domain.llm.client.LlmClient;
import com.aistar.backend.global.apiPayload.code.ErrorStatus;
import com.aistar.backend.global.apiPayload.exception.ProjectException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

    private final ChatRepository chatRepository;
    private final TurnRepository turnRepository;
    private final MessageRepository messageRepository;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    private static final long SSE_TIMEOUT = 60_000L * 5;

    // 스트리밍 중인 메시지 ID → 스트리밍 컨텍스트
    private final Map<Long, StreamingContext> streamingContexts = new ConcurrentHashMap<>();

    // ── 스트리밍 컨텍스트 (cancel 경합 해결) ──

    record StreamingContext(AtomicBoolean canceled, StringBuffer contentBuffer) {
        static StreamingContext create() {
            return new StreamingContext(new AtomicBoolean(false), new StringBuffer());
        }
    }

    // ── Turn + Message 생성 (소유권 검증 포함) ──

    @Transactional
    public TurnContext createTurnAndMessages(Long memberId, Long chatId, String userContent) {
        Chat chat = chatRepository.findByIdAndDeletedAtIsNull(chatId)
                .orElseThrow(() -> new ProjectException(ErrorStatus.CHAT_NOT_FOUND));

        if (!chat.getMember().getId().equals(memberId)) {
            throw new ProjectException(ErrorStatus.FORBIDDEN);
        }

        int nextSequence = turnRepository.findTopByChatIdOrderByTurnSequenceDesc(chatId)
                .map(t -> t.getTurnSequence() + 1)
                .orElse(1);

        Turn turn = Turn.builder()
                .chat(chat)
                .turnSequence(nextSequence)
                .build();
        turnRepository.save(turn);

        Message userMessage = Message.builder()
                .turn(turn)
                .senderType(SenderType.USER)
                .status(MessageStatus.COMPLETED)
                .content(userContent)
                .build();
        messageRepository.save(userMessage);

        Message aiMessage = Message.builder()
                .turn(turn)
                .senderType(SenderType.ASSISTANT)
                .status(MessageStatus.STREAMING)
                .build();
        messageRepository.save(aiMessage);

        return new TurnContext(chat, turn, userMessage, aiMessage);
    }

    // ── SSE 스트리밍 ──

    public SseEmitter streamMessage(TurnContext ctx) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        Long aiMessageId = ctx.aiMessage().getId();

        StreamingContext streamCtx = StreamingContext.create();
        streamingContexts.put(aiMessageId, streamCtx);

        Thread.startVirtualThread(() -> {
            try {
                // 1. turn_started
                sendEvent(emitter, "turn_started", MessageResDto.TurnStarted.builder()
                        .turnId(ctx.turn().getId())
                        .userMessageId(ctx.userMessage().getId())
                        .aiMessageId(aiMessageId)
                        .build());

                // 2. LLM 스트리밍
                llmClient.streamCompletion(
                        ctx.chat().getLlmModel().getModelId(),
                        ctx.userMessage().getContent(),
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

                // 3. 완료 처리
                String fullContent = streamCtx.contentBuffer().toString();
                String summary = fullContent.length() > 50
                        ? fullContent.substring(0, 50) : fullContent;
                int answerToken = fullContent.split("\\s+").length;

                transactionTemplate.executeWithoutResult(status -> {
                    Message message = messageRepository.findById(aiMessageId).orElseThrow();
                    if (message.getStatus() == MessageStatus.STREAMING) {
                        message.updateContent(fullContent);
                        message.updateStatus(MessageStatus.COMPLETED);
                        message.updateAnswerToken(answerToken);
                    }
                    Turn turn = turnRepository.findById(ctx.turn().getId()).orElseThrow();
                    turn.updateSummary(summary);
                    Chat chat = chatRepository.findById(ctx.chat().getId()).orElseThrow();
                    chat.touchUpdatedAt();
                });

                sendEvent(emitter, "turn_completed", MessageResDto.TurnCompleted.builder()
                        .aiMessageId(aiMessageId)
                        .summary(summary)
                        .answerToken(answerToken)
                        .build());

                emitter.complete();

            } catch (CancelException e) {
                // cancel API에서 이미 content 저장 + status 변경 처리했으므로 SSE만 종료
                transactionTemplate.executeWithoutResult(status -> {
                    Chat chat = chatRepository.findById(ctx.chat().getId()).orElseThrow();
                    chat.touchUpdatedAt();
                });
                emitter.complete();

            } catch (Exception e) {
                log.error("SSE 스트리밍 실패", e);
                try {
                    transactionTemplate.executeWithoutResult(status -> {
                        Message message = messageRepository.findById(aiMessageId).orElseThrow();
                        message.updateStatus(MessageStatus.FAILED);
                        Chat chat = chatRepository.findById(ctx.chat().getId()).orElseThrow();
                        chat.touchUpdatedAt();
                    });
                    sendEvent(emitter, "error", MessageResDto.SseError.builder()
                            .code(ErrorStatus.LLM_CALL_FAILED.getCode())
                            .message(ErrorStatus.LLM_CALL_FAILED.getMessage())
                            .build());
                } catch (IOException ignored) {
                }
                emitter.completeWithError(e);
            } finally {
                streamingContexts.remove(aiMessageId);
            }
        });

        return emitter;
    }

    // ── Cancel ──

    @Transactional
    public MessageResDto.CancelResult cancelMessage(Long memberId, Long chatId, Long messageId) {
        // 1. chat 소유권
        Chat chat = chatRepository.findByIdAndDeletedAtIsNull(chatId)
                .orElseThrow(() -> new ProjectException(ErrorStatus.CHAT_NOT_FOUND));
        if (!chat.getMember().getId().equals(memberId)) {
            throw new ProjectException(ErrorStatus.FORBIDDEN);
        }

        // 2. 메시지 존재 + chatId 소속 확인
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new ProjectException(ErrorStatus.MESSAGE_NOT_FOUND));
        if (!message.getTurn().getChat().getId().equals(chatId)) {
            throw new ProjectException(ErrorStatus.MESSAGE_NOT_FOUND);
        }

        // 3. AI 메시지만 cancel 가능
        if (message.getSenderType() != SenderType.ASSISTANT) {
            throw new ProjectException(ErrorStatus.MESSAGE_CANCEL_NOT_ALLOWED);
        }

        // 4. 상태 전이
        String partialContent = null;
        Integer partialToken = null;

        switch (message.getStatus()) {
            case STREAMING -> {
                // cancel 시그널 + buffer에서 부분 content 읽기
                StreamingContext streamCtx = streamingContexts.get(messageId);
                if (streamCtx != null) {
                    streamCtx.canceled().set(true);
                    String buffered = streamCtx.contentBuffer().toString();
                    if (!buffered.isEmpty()) {
                        partialContent = buffered;
                        partialToken = buffered.split("\\s+").length;
                    }
                }

                message.updateStatus(MessageStatus.CANCELED);
                if (partialContent != null) {
                    message.updateContent(partialContent);
                    message.updateAnswerToken(partialToken);
                }
            }
            case CANCELED -> {
                // idempotent — 기존 값 그대로 반환
                partialContent = message.getContent();
                partialToken = message.getAnswerToken();
            }
            case COMPLETED, FAILED -> {
                throw new ProjectException(ErrorStatus.MESSAGE_CANCEL_NOT_ALLOWED);
            }
        }

        chat.touchUpdatedAt();

        return MessageResDto.CancelResult.builder()
                .messageId(message.getId())
                .status(message.getStatus())
                .content(partialContent)
                .answerToken(partialToken)
                .build();
    }

    // ── 내부 유틸 ──

    private void sendEvent(SseEmitter emitter, String eventName, Object data) throws IOException {
        emitter.send(SseEmitter.event()
                .name(eventName)
                .data(objectMapper.writeValueAsString(data)));
    }

    public record TurnContext(Chat chat, Turn turn, Message userMessage, Message aiMessage) {}

    private static class CancelException extends RuntimeException {}
}
