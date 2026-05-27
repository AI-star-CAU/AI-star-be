package com.aistar.backend.domain.chat.service;

import com.aistar.backend.domain.llm.client.AiServerClient;
import com.aistar.backend.domain.llm.client.AiServerException;
import com.aistar.backend.domain.llm.dto.AiChatRequest;
import com.aistar.backend.domain.chat.dto.MessageResDto;
import com.aistar.backend.domain.chat.entity.Chat;
import com.aistar.backend.domain.chat.entity.Message;
import com.aistar.backend.domain.chat.entity.Turn;
import com.aistar.backend.domain.chat.enums.MessageStatus;
import com.aistar.backend.domain.chat.enums.SenderType;
import com.aistar.backend.domain.chat.enums.TitleStatus;
import com.aistar.backend.domain.chat.repository.ChatRepository;
import com.aistar.backend.domain.chat.repository.MessageRepository;
import com.aistar.backend.domain.chat.repository.TurnRepository;
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
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

    private final ChatRepository chatRepository;
    private final TurnRepository turnRepository;
    private final MessageRepository messageRepository;
    private final ChatService chatService;
    private final ContextAssembler contextAssembler;
    private final AiServerClient aiServerClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final com.aistar.backend.domain.usage.service.UsageService usageService;

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
        Chat chat = chatRepository.findByIdWithMemberAndDeletedAtIsNull(chatId)
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
            final ContextAssembler.ContextResult[] ctxHolder = {null};
            try {
                // 0. branch_created (재생성/수정 시)
                if (ctx.branchCreated() != null) {
                    sendEvent(emitter, "branch_created", ctx.branchCreated());
                }

                // 1. turn_started
                sendEvent(emitter, "turn_started", MessageResDto.TurnStarted.builder()
                        .chatId(ctx.chat().getId())
                        .turnId(ctx.turn().getId())
                        .userMessageId(ctx.userMessage().getId())
                        .aiMessageId(aiMessageId)
                        .build());

                // 2. 맥락 조립 + 압축 (§3.1, §3.2)
                ctxHolder[0] = contextAssembler.buildContext(
                        ctx.chat(), ctx.turn(), ctx.userMessage().getContent());

                // 3. AI server streaming
                aiServerClient.streamChatCompletion(
                        new AiChatRequest(
                                ctx.userMessage().getContent(),
                                512,
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

                // 4. 완료 처리
                String fullContent = streamCtx.contentBuffer().toString();
                int answerToken = fullContent.split("\\s+").length;
                Long memberId = ctx.chat().getMember().getId();

                transactionTemplate.executeWithoutResult(status -> {
                    Message message = messageRepository.findById(aiMessageId).orElseThrow();
                    if (message.getStatus() == MessageStatus.STREAMING) {
                        message.updateContent(fullContent);
                        message.updateStatus(MessageStatus.COMPLETED);
                        message.updateAnswerToken(answerToken);
                        message.updatePromptToken(ctxHolder[0].contextTokens());
                    }
                    Chat chat = chatRepository.findById(ctx.chat().getId()).orElseThrow();
                    chat.updateLastTurnId(ctx.turn().getId());
                    chat.touchUpdatedAt();
                    chatService.touchAncestorChain(ctx.chat().getId());
                    usageService.accumulateUsage(memberId,
                            ctxHolder[0].contextTokens(), answerToken,
                            ctxHolder[0].compressedTurnCount());
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

                // 비동기 summary 생성
                generateSummaryAsync(ctx.turn().getId(), fullContent);

            } catch (CancelException e) {
                String partialContent = streamCtx.contentBuffer().toString();
                Integer partialToken = partialContent.isEmpty() ? null : partialContent.split("\\s+").length;

                transactionTemplate.executeWithoutResult(status -> {
                    Chat chat = chatRepository.findById(ctx.chat().getId()).orElseThrow();
                    chat.touchUpdatedAt();
                    if (ctxHolder[0] != null) {
                        usageService.accumulateUsage(ctx.chat().getMember().getId(),
                                ctxHolder[0].contextTokens(),
                                partialToken != null ? partialToken : 0,
                                ctxHolder[0].compressedTurnCount());
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
                try {
                    transactionTemplate.executeWithoutResult(status -> {
                        Message message = messageRepository.findById(aiMessageId).orElseThrow();
                        message.updateStatus(MessageStatus.FAILED);
                        Chat chat = chatRepository.findById(ctx.chat().getId()).orElseThrow();
                        chat.touchUpdatedAt();
                        if (ctxHolder[0] != null) {
                            usageService.accumulateUsage(ctx.chat().getMember().getId(),
                                    ctxHolder[0].contextTokens(), 0,
                                    ctxHolder[0].compressedTurnCount());
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
                streamingContexts.remove(aiMessageId);
            }
        });

        return emitter;
    }

    // ── Cancel ──

    @Transactional
    public MessageResDto.CancelResult cancelMessage(Long memberId, Long chatId, Long messageId) {
        // 1. chat 소유권
        Chat chat = chatRepository.findByIdWithMemberAndDeletedAtIsNull(chatId)
                .orElseThrow(() -> new ProjectException(ErrorStatus.CHAT_NOT_FOUND));
        if (!chat.getMember().getId().equals(memberId)) {
            throw new ProjectException(ErrorStatus.FORBIDDEN);
        }

        // 2. 메시지 존재 + chatId 소속 확인 (fetch join으로 turn→chat 즉시 로딩)
        Message message = messageRepository.findByIdWithTurnAndChat(messageId)
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

    // ── 응답 재생성 (§4.1) ──

    @Transactional
    public TurnContext regenerateMessage(Long memberId, Long chatId, Long messageId) {
        Chat chat = chatRepository.findByIdWithMemberAndDeletedAtIsNull(chatId)
                .orElseThrow(() -> new ProjectException(ErrorStatus.CHAT_NOT_FOUND));
        if (!chat.getMember().getId().equals(memberId)) {
            throw new ProjectException(ErrorStatus.FORBIDDEN);
        }

        Message message = messageRepository.findByIdWithTurnAndChat(messageId)
                .orElseThrow(() -> new ProjectException(ErrorStatus.MESSAGE_NOT_FOUND));
        if (!message.getTurn().getChat().getId().equals(chatId)) {
            throw new ProjectException(ErrorStatus.MESSAGE_NOT_FOUND);
        }
        if (message.getSenderType() != SenderType.ASSISTANT) {
            throw new ProjectException(ErrorStatus.MESSAGE_ACTION_NOT_ALLOWED);
        }

        // STREAMING 중이면 기존 스트리밍을 cancel한 후 진행
        if (message.getStatus() == MessageStatus.STREAMING) {
            StreamingContext streamCtx = streamingContexts.get(messageId);
            if (streamCtx != null) {
                streamCtx.canceled().set(true);
            }
        }

        Turn targetTurn = message.getTurn();

        // 원본 user 메시지
        Message userMessage = targetTurn.getMessages().stream()
                .filter(m -> m.getSenderType() == SenderType.USER)
                .findFirst()
                .orElseThrow(() -> new ProjectException(ErrorStatus.MESSAGE_ACTION_NOT_ALLOWED));

        // 기존 AI 메시지를 초기화하여 재사용
        message.updateContent(null);
        message.updateAnswerToken(null);
        message.updateStatus(MessageStatus.STREAMING);

        return new TurnContext(chat, targetTurn, userMessage, message);
    }

    // ── 메시지 수정 (§4.2) ──

    @Transactional
    public TurnContext editMessage(Long memberId, Long chatId, Long messageId, String content) {
        Chat chat = chatRepository.findByIdWithMemberAndDeletedAtIsNull(chatId)
                .orElseThrow(() -> new ProjectException(ErrorStatus.CHAT_NOT_FOUND));
        if (!chat.getMember().getId().equals(memberId)) {
            throw new ProjectException(ErrorStatus.FORBIDDEN);
        }

        Message message = messageRepository.findByIdWithTurnAndChat(messageId)
                .orElseThrow(() -> new ProjectException(ErrorStatus.MESSAGE_NOT_FOUND));
        if (!message.getTurn().getChat().getId().equals(chatId)) {
            throw new ProjectException(ErrorStatus.MESSAGE_NOT_FOUND);
        }
        if (message.getSenderType() != SenderType.USER) {
            throw new ProjectException(ErrorStatus.MESSAGE_ACTION_NOT_ALLOWED);
        }
        if (message.getStatus() == MessageStatus.STREAMING) {
            throw new ProjectException(ErrorStatus.MESSAGE_ACTION_NOT_ALLOWED);
        }

        Turn targetTurn = message.getTurn();
        Turn branchPointTurn = resolveBranchPoint(targetTurn, chat);

        return createBranchTurn(chat, branchPointTurn, content);
    }

    // ── 분기 생성 공통 로직 ──

    private TurnContext createBranchTurn(Chat sourceChat, Turn branchPointTurn, String userContent) {
        Chat branch = Chat.builder()
                .title(null)
                .titleStatus(TitleStatus.PENDING)
                .llmProvider(sourceChat.getLlmProvider())
                .llmModel(sourceChat.getLlmModel())
                .member(sourceChat.getMember())
                .parentId(branchPointTurn.getChat().getId())
                .branchPointTurnId(branchPointTurn.getId())
                .rootChatId(sourceChat.getRootChatId())
                .build();
        chatRepository.save(branch);

        Turn newTurn = Turn.builder()
                .chat(branch)
                .turnSequence(1)
                .build();
        turnRepository.save(newTurn);

        Message newUserMessage = Message.builder()
                .turn(newTurn)
                .senderType(SenderType.USER)
                .status(MessageStatus.COMPLETED)
                .content(userContent)
                .build();
        messageRepository.save(newUserMessage);

        Message newAiMessage = Message.builder()
                .turn(newTurn)
                .senderType(SenderType.ASSISTANT)
                .status(MessageStatus.STREAMING)
                .build();
        messageRepository.save(newAiMessage);

        MessageResDto.BranchCreated branchCreated = MessageResDto.BranchCreated.builder()
                .newChatId(branch.getId())
                .branchPointTurnId(branchPointTurn.getId())
                .title(branch.getTitle())
                .titleStatus(branch.getTitleStatus())
                .build();

        return new TurnContext(branch, newTurn, newUserMessage, newAiMessage, branchCreated);
    }

    // ── 분기점 결정 (§4.1.1) ──

    private Turn resolveBranchPoint(Turn targetTurn, Chat chat) {
        if (targetTurn.getTurnSequence() > 1) {
            // Rule 1: 같은 chat의 직전 turn
            return turnRepository.findByChatIdAndTurnSequence(
                            chat.getId(), targetTurn.getTurnSequence() - 1)
                    .orElseThrow(() -> new ProjectException(ErrorStatus.TURN_NOT_FOUND));
        }

        if (chat.getParentId() != null && chat.getBranchPointTurnId() != null) {
            // Rule 2: 현재 chat이 branch → 부모의 branchPointTurn
            return turnRepository.findById(chat.getBranchPointTurnId())
                    .orElseThrow(() -> new ProjectException(ErrorStatus.TURN_NOT_FOUND));
        }

        // Rule 3: root의 첫 turn → 거부
        throw new ProjectException(ErrorStatus.MESSAGE_ACTION_NOT_ALLOWED);
    }

    // ── 비동기 summary 생성 ──

    private void generateSummaryAsync(Long turnId, String fullContent) {
        Thread.startVirtualThread(() -> {
            try {
                var response = aiServerClient.generateSummary(
                        new com.aistar.backend.domain.llm.dto.AiSummaryRequest(turnId, fullContent));
                String summary = response != null && response.summary() != null
                        ? response.summary()
                        : fullContent.length() > 50 ? fullContent.substring(0, 50) : fullContent;
                transactionTemplate.executeWithoutResult(status -> {
                    Turn turn = turnRepository.findById(turnId).orElseThrow();
                    turn.updateSummary(summary);
                });
            } catch (Exception e) {
                log.error("Summary 생성 실패 turnId={}, AI 요약 실패 — fallback 사용", turnId, e);
                try {
                    String fallback = fullContent.length() > 50
                            ? fullContent.substring(0, 50) : fullContent;
                    transactionTemplate.executeWithoutResult(status -> {
                        Turn turn = turnRepository.findById(turnId).orElseThrow();
                        turn.updateSummary(fallback);
                    });
                } catch (Exception ex) {
                    log.error("Summary fallback 저장 실패 turnId={}", turnId, ex);
                }
            }
        });
    }

    // ── 내부 유틸 ──

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

    public record TurnContext(Chat chat, Turn turn, Message userMessage, Message aiMessage,
                               MessageResDto.BranchCreated branchCreated) {
        TurnContext(Chat chat, Turn turn, Message userMessage, Message aiMessage) {
            this(chat, turn, userMessage, aiMessage, null);
        }
    }

    private static class CancelException extends RuntimeException {}
}
