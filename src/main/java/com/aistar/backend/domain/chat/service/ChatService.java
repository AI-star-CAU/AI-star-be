package com.aistar.backend.domain.chat.service;

import com.aistar.backend.domain.chat.converter.ChatConverter;
import com.aistar.backend.domain.chat.dto.ChatReqDto;
import com.aistar.backend.domain.chat.dto.ChatResDto;
import com.aistar.backend.domain.chat.entity.Chat;
import com.aistar.backend.domain.chat.repository.ChatRepository;
import com.aistar.backend.domain.member.entity.Member;
import com.aistar.backend.domain.member.repository.MemberRepository;
import com.aistar.backend.global.apiPayload.code.ErrorStatus;
import com.aistar.backend.global.apiPayload.exception.ProjectException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatRepository chatRepository;
    private final MemberRepository memberRepository;

    @Transactional
    public ChatResDto.Detail createChat(Long memberId, ChatReqDto.Create dto) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new ProjectException(ErrorStatus.MEMBER_NOT_FOUND));

        Chat chat = Chat.builder()
                .title(dto.title() != null ? dto.title() : "제목없음")
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
        return ChatConverter.toPageInfo(page);
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
