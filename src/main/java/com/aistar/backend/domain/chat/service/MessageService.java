package com.aistar.backend.domain.chat.service;

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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

    private final ChatRepository chatRepository;
    private final TurnRepository turnRepository;
    private final MessageRepository messageRepository;
    private final MessageStreamingService messageStreamingService;
    private final StreamingRegistry streamingRegistry;

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

        return new TurnContext(memberId, chat, turn, userMessage, aiMessage);
    }

    // ── SSE 스트리밍 ──

    public SseEmitter streamMessage(TurnContext ctx) {
        return messageStreamingService.streamMessage(ctx);
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
                StreamingRegistry.StreamingContext streamCtx = streamingRegistry.find(messageId);
                if (streamCtx != null) {
                    streamingRegistry.cancel(messageId);
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
            streamingRegistry.cancel(messageId);
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

        return new TurnContext(memberId, chat, targetTurn, userMessage, message);
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

        return new TurnContext(sourceChat.getMember().getId(), branch, newTurn, newUserMessage, newAiMessage, branchCreated);
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

    public record TurnContext(Long memberId, Chat chat, Turn turn, Message userMessage, Message aiMessage,
                               MessageResDto.BranchCreated branchCreated) {
        TurnContext(Long memberId, Chat chat, Turn turn, Message userMessage, Message aiMessage) {
            this(memberId, chat, turn, userMessage, aiMessage, null);
        }
    }
}
