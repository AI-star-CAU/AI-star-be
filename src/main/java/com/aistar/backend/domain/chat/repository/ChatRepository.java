package com.aistar.backend.domain.chat.repository;

import com.aistar.backend.domain.chat.entity.Chat;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ChatRepository extends JpaRepository<Chat, Long> {

    // 내 대화 목록 (루트 세션만, 삭제되지 않은 것)
    Page<Chat> findByMemberIdAndParentIdIsNullAndDeletedAtIsNull(Long memberId, Pageable pageable);

    // 내 대화 단건 조회 (삭제되지 않은 것)
    Optional<Chat> findByIdAndDeletedAtIsNull(Long chatId);
}
