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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

    private final ChatRepository chatRepository;
    private final TurnRepository turnRepository;
    private final MessageRepository messageRepository;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    private static final long SSE_TIMEOUT = 60_000L * 5;

    // 스트리밍 중인 메시지 ID → cancel 플래그
    private final Map<Long, Boolean> cancelFlags = new ConcurrentHashMap<>();

    @Transactional(readOnly = true)
    public Chat validateAndGetChat(Long memberId, Long chatId) {
        Chat chat = chatRepository.findByIdAndDeletedAtIsNull(chatId)
                .orElseThrow(() -> new ProjectException(ErrorStatus.CHAT_NOT_FOUND));

        if (!chat.getMember().getId().equals(memberId)) {
            throw new ProjectException(ErrorStatus.FORBIDDEN);
        }
        return chat;
    }

    @Transactional
    public TurnContext createTurnAndMessages(Long chatId, String userContent) {
        Chat chat = chatRepository.findByIdAndDeletedAtIsNull(chatId)
                .orElseThrow(() -> new ProjectException(ErrorStatus.CHAT_NOT_FOUND));

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
                .senderType(SenderType.AI)
                .status(MessageStatus.STREAMING)
                .build();
        messageRepository.save(aiMessage);

        return new TurnContext(chat, turn, userMessage, aiMessage);
    }

    public SseEmitter streamMessage(TurnContext ctx) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        Long aiMessageId = ctx.aiMessage().getId();

        cancelFlags.put(aiMessageId, false);

        Thread.startVirtualThread(() -> {
            StringBuilder contentBuilder = new StringBuilder();
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
                            // cancel 체크
                            if (Boolean.TRUE.equals(cancelFlags.get(aiMessageId))) {
                                throw new CancelException();
                            }
                            contentBuilder.append(chunk);
                            try {
                                sendEvent(emitter, "chunk", MessageResDto.Chunk.builder()
                                        .text(chunk).build());
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }
                );

                // 3. 완료 처리
                String fullContent = contentBuilder.toString();
                String summary = fullContent.length() > 50
                        ? fullContent.substring(0, 50) : fullContent;
                int answerToken = fullContent.split("\\s+").length;

                completeAiMessage(aiMessageId, fullContent, answerToken);
                updateTurnSummary(ctx.turn().getId(), summary);
                updateChatTimestamp(ctx.chat().getId());

                sendEvent(emitter, "turn_completed", MessageResDto.TurnCompleted.builder()
                        .aiMessageId(aiMessageId)
                        .summary(summary)
                        .answerToken(answerToken)
                        .build());

                emitter.complete();

            } catch (CancelException e) {
                // cancel API에서 이미 DB 처리했으므로 SSE만 종료
                String partialContent = contentBuilder.toString();
                if (!partialContent.isEmpty()) {
                    savePartialContent(aiMessageId, partialContent);
                }
                updateChatTimestamp(ctx.chat().getId());
                emitter.complete();

            } catch (Exception e) {
                log.error("SSE 스트리밍 실패", e);
                try {
                    failAiMessage(aiMessageId);
                    updateChatTimestamp(ctx.chat().getId());
                    sendEvent(emitter, "error", MessageResDto.SseError.builder()
                            .code(ErrorStatus.LLM_CALL_FAILED.getCode())
                            .message(ErrorStatus.LLM_CALL_FAILED.getMessage())
                            .build());
                } catch (IOException ignored) {
                }
                emitter.completeWithError(e);
            } finally {
                cancelFlags.remove(aiMessageId);
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

        // 3. 상태 전이
        switch (message.getStatus()) {
            case STREAMING -> {
                message.updateStatus(MessageStatus.CANCELED);
                // 스트리밍 스레드에 cancel 시그널
                cancelFlags.put(messageId, true);
            }
            case CANCELED -> {
                // idempotent
            }
            case COMPLETED, FAILED -> {
                throw new ProjectException(ErrorStatus.MESSAGE_CANCEL_NOT_ALLOWED);
            }
        }

        chat.touchUpdatedAt();

        return MessageResDto.CancelResult.builder()
                .messageId(message.getId())
                .status(message.getStatus())
                .content(message.getContent())
                .answerToken(message.getAnswerToken())
                .build();
    }

    // ── 내부 메서드 ──

    @Transactional
    public void completeAiMessage(Long messageId, String content, int answerToken) {
        Message message = messageRepository.findById(messageId).orElseThrow();
        // cancel된 경우 덮어쓰지 않음
        if (message.getStatus() == MessageStatus.STREAMING) {
            message.updateContent(content);
            message.updateStatus(MessageStatus.COMPLETED);
            message.updateAnswerToken(answerToken);
        }
    }

    @Transactional
    public void savePartialContent(Long messageId, String partialContent) {
        Message message = messageRepository.findById(messageId).orElseThrow();
        message.updateContent(partialContent);
        int partialToken = partialContent.split("\\s+").length;
        message.updateAnswerToken(partialToken);
    }

    @Transactional
    public void failAiMessage(Long messageId) {
        Message message = messageRepository.findById(messageId).orElseThrow();
        message.updateStatus(MessageStatus.FAILED);
    }

    @Transactional
    public void updateTurnSummary(Long turnId, String summary) {
        Turn turn = turnRepository.findById(turnId).orElseThrow();
        turn.updateSummary(summary);
    }

    @Transactional
    public void updateChatTimestamp(Long chatId) {
        Chat chat = chatRepository.findById(chatId).orElseThrow();
        chat.touchUpdatedAt();
    }

    private void sendEvent(SseEmitter emitter, String eventName, Object data) throws IOException {
        emitter.send(SseEmitter.event()
                .name(eventName)
                .data(objectMapper.writeValueAsString(data)));
    }

    public record TurnContext(Chat chat, Turn turn, Message userMessage, Message aiMessage) {}

    // 스트리밍 스레드 내부에서 cancel 감지용
    private static class CancelException extends RuntimeException {}
}
