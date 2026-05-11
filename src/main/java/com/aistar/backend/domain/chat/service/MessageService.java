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

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

    private final ChatRepository chatRepository;
    private final TurnRepository turnRepository;
    private final MessageRepository messageRepository;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    private static final long SSE_TIMEOUT = 60_000L * 5; // 5분

    /**
     * 스트리밍 시작 전 검증 (Controller에서 호출, 트랜잭션 내)
     */
    @Transactional(readOnly = true)
    public Chat validateAndGetChat(Long memberId, Long chatId) {
        Chat chat = chatRepository.findByIdAndDeletedAtIsNull(chatId)
                .orElseThrow(() -> new ProjectException(ErrorStatus.CHAT_NOT_FOUND));

        if (!chat.getMember().getId().equals(memberId)) {
            throw new ProjectException(ErrorStatus.FORBIDDEN);
        }
        return chat;
    }

    /**
     * Turn + Messages 생성 (트랜잭션 내)
     */
    @Transactional
    public TurnContext createTurnAndMessages(Long chatId, String userContent) {
        Chat chat = chatRepository.findByIdAndDeletedAtIsNull(chatId)
                .orElseThrow(() -> new ProjectException(ErrorStatus.CHAT_NOT_FOUND));

        // 다음 turnSequence 계산
        int nextSequence = turnRepository.findTopByChatIdOrderByTurnSequenceDesc(chatId)
                .map(t -> t.getTurnSequence() + 1)
                .orElse(1);

        Turn turn = Turn.builder()
                .chat(chat)
                .turnSequence(nextSequence)
                .build();
        turnRepository.save(turn);

        // USER 메시지 (COMPLETED)
        Message userMessage = Message.builder()
                .turn(turn)
                .senderType(SenderType.USER)
                .status(MessageStatus.COMPLETED)
                .content(userContent)
                .build();
        messageRepository.save(userMessage);

        // AI 메시지 (STREAMING)
        Message aiMessage = Message.builder()
                .turn(turn)
                .senderType(SenderType.AI)
                .status(MessageStatus.STREAMING)
                .build();
        messageRepository.save(aiMessage);

        return new TurnContext(chat, turn, userMessage, aiMessage);
    }

    /**
     * SSE 스트리밍 실행 (비동기, 트랜잭션 밖에서 호출)
     */
    public SseEmitter streamMessage(TurnContext ctx) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);

        Thread.startVirtualThread(() -> {
            try {
                // 1. turn_started
                sendEvent(emitter, "turn_started", MessageResDto.TurnStarted.builder()
                        .turnId(ctx.turn().getId())
                        .userMessageId(ctx.userMessage().getId())
                        .aiMessageId(ctx.aiMessage().getId())
                        .build());

                // 2. LLM 스트리밍 → chunk 이벤트
                StringBuilder contentBuilder = new StringBuilder();

                llmClient.streamCompletion(
                        ctx.chat().getLlmModel().getModelId(),
                        ctx.userMessage().getContent(),
                        chunk -> {
                            contentBuilder.append(chunk);
                            try {
                                sendEvent(emitter, "chunk", MessageResDto.Chunk.builder()
                                        .text(chunk)
                                        .build());
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }
                );

                // 3. 완료 처리
                String fullContent = contentBuilder.toString();
                String summary = fullContent.length() > 50
                        ? fullContent.substring(0, 50)
                        : fullContent;
                int answerToken = fullContent.split("\\s+").length; // 간이 토큰 계산

                completeAiMessage(ctx.aiMessage().getId(), fullContent, answerToken);
                updateTurnSummary(ctx.turn().getId(), summary);
                updateChatTimestamp(ctx.chat().getId());

                // 4. turn_completed
                sendEvent(emitter, "turn_completed", MessageResDto.TurnCompleted.builder()
                        .aiMessageId(ctx.aiMessage().getId())
                        .summary(summary)
                        .answerToken(answerToken)
                        .build());

                emitter.complete();

            } catch (Exception e) {
                log.error("SSE 스트리밍 실패", e);
                try {
                    failAiMessage(ctx.aiMessage().getId());
                    updateChatTimestamp(ctx.chat().getId());
                    sendEvent(emitter, "error", MessageResDto.SseError.builder()
                            .code(ErrorStatus.LLM_CALL_FAILED.getCode())
                            .message(ErrorStatus.LLM_CALL_FAILED.getMessage())
                            .build());
                } catch (IOException ignored) {
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    @Transactional
    public void completeAiMessage(Long messageId, String content, int answerToken) {
        Message message = messageRepository.findById(messageId).orElseThrow();
        message.updateContent(content);
        message.updateStatus(MessageStatus.COMPLETED);
        message.updateAnswerToken(answerToken);
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
}
