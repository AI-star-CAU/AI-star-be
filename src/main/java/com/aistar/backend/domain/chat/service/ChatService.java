package com.aistar.backend.domain.chat.service;

import com.aistar.backend.domain.chat.converter.ChatConverter;
import com.aistar.backend.domain.chat.dto.ChatReqDto;
import com.aistar.backend.domain.chat.dto.ChatResDto;
import com.aistar.backend.domain.chat.entity.Chat;
import com.aistar.backend.domain.chat.entity.Message;
import com.aistar.backend.domain.chat.enums.TitleStatus;
import com.aistar.backend.domain.chat.repository.ChatRepository;
import com.aistar.backend.domain.chat.repository.MessageRepository;
import com.aistar.backend.domain.chat.repository.TurnRepository;
import com.aistar.backend.domain.member.entity.Member;
import com.aistar.backend.domain.member.repository.MemberRepository;
import com.aistar.backend.global.apiPayload.code.ErrorStatus;
import com.aistar.backend.global.apiPayload.exception.ProjectException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatRepository chatRepository;
    private final TurnRepository turnRepository;
    private final MessageRepository messageRepository;
    private final MemberRepository memberRepository;

    @Transactional
    public ChatResDto.Detail createChat(Long memberId, ChatReqDto.Create dto) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new ProjectException(ErrorStatus.MEMBER_NOT_FOUND));

        Chat chat = Chat.builder()
                .title(dto.title() != null ? dto.title() : "제목없음")
                .titleStatus(dto.title() != null ? TitleStatus.USER_EDITED : TitleStatus.PENDING)
                .llmProvider(dto.llmProvider())
                .llmModel(dto.llmModel())
                .member(member)
                .build();

        chatRepository.save(chat);
        chat.initRootChatId();

        return ChatConverter.toDetail(chat);
    }

    @Transactional(readOnly = true)
    public ChatResDto.PageInfo getChatList(Long memberId, Pageable pageable) {
        Page<Chat> page = chatRepository.findByMemberIdAndParentIdIsNullAndDeletedAtIsNull(memberId, pageable);

        List<Long> chatIds = page.getContent().stream().map(Chat::getId).toList();

        Map<Long, Long> turnCounts = Map.of();
        Map<Long, Message> latestMessages = Map.of();

        if (!chatIds.isEmpty()) {
            turnCounts = turnRepository.countByChatIds(chatIds).stream()
                    .collect(Collectors.toMap(r -> (Long) r[0], r -> (Long) r[1]));
            latestMessages = messageRepository.findLatestByChatIds(chatIds).stream()
                    .collect(Collectors.toMap(m -> m.getTurn().getChat().getId(), m -> m));
        }

        return ChatConverter.toPageInfo(page, turnCounts, latestMessages);
    }

    @Transactional(readOnly = true)
    public ChatResDto.Detail getChatDetail(Long memberId, Long chatId) {
        Chat chat = findChatOrThrow(chatId);
        validateOwner(chat, memberId);
        return ChatConverter.toDetail(chat);
    }

    @Transactional
    public ChatResDto.Detail createBranch(Long memberId, Long chatId, ChatReqDto.BranchCreate dto) {
        // 1. 부모 chat 검증
        Chat parentChat = findChatOrThrow(chatId);
        validateOwner(parentChat, memberId);

        // 2. branchPointTurn 검증 (해당 chat에 속하는 turn인지)
        turnRepository.findByIdAndChatId(dto.branchPointTurnId(), chatId)
                .orElseThrow(() -> new ProjectException(ErrorStatus.BRANCH_INVALID));

        // 3. 새 분기 chat 생성
        boolean userProvidedTitle = dto.title() != null && !dto.title().isBlank();

        Chat branch = Chat.builder()
                .title(userProvidedTitle ? dto.title() : "새 분기")
                .titleStatus(userProvidedTitle ? TitleStatus.USER_EDITED : TitleStatus.PENDING)
                .llmProvider(parentChat.getLlmProvider())
                .llmModel(parentChat.getLlmModel())
                .member(parentChat.getMember())
                .parentId(chatId)
                .branchPointTurnId(dto.branchPointTurnId())
                .rootChatId(parentChat.getRootChatId())
                .build();

        chatRepository.save(branch);

        return ChatConverter.toDetail(branch);
    }

    @Transactional
    public ChatResDto.Detail updateChatTitle(Long memberId, Long chatId, ChatReqDto.UpdateTitle dto) {
        Chat chat = findChatOrThrow(chatId);
        validateOwner(chat, memberId);
        chat.updateTitle(dto.title());
        return ChatConverter.toDetail(chat);
    }

    @Transactional
    public void deleteChat(Long memberId, Long chatId) {
        Chat chat = chatRepository.findById(chatId)
                .orElseThrow(() -> new ProjectException(ErrorStatus.CHAT_NOT_FOUND));
        validateOwner(chat, memberId);

        if (chat.getDeletedAt() != null) {
            throw new ProjectException(ErrorStatus.BRANCH_ALREADY_DELETED);
        }

        // cascade: 자손 chat도 함께 soft delete
        softDeleteCascade(chat);
    }

    private void softDeleteCascade(Chat chat) {
        chat.softDelete();
        List<Chat> children = chatRepository.findAllByParentId(chat.getId());
        for (Chat child : children) {
            if (child.getDeletedAt() == null) {
                softDeleteCascade(child);
            }
        }
    }

    private Chat findChatOrThrow(Long chatId) {
        return chatRepository.findByIdAndDeletedAtIsNull(chatId)
                .orElseThrow(() -> new ProjectException(ErrorStatus.CHAT_NOT_FOUND));
    }

    private void validateOwner(Chat chat, Long memberId) {
        if (!chat.getMember().getId().equals(memberId)) {
            throw new ProjectException(ErrorStatus.FORBIDDEN);
        }
    }
}
