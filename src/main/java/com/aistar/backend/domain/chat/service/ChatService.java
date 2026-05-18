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
    public void deleteChat(Long memberId, Long chatId) {
        Chat chat = findChatOrThrow(chatId);
        validateOwner(chat, memberId);
        chat.softDelete();
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
