package com.aistar.backend.domain.chat.repository;

import com.aistar.backend.domain.chat.entity.Chat;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChatRepository extends JpaRepository<Chat, Long> {

    // 내 대화 목록 (루트 세션만, 삭제되지 않은 것)
    Page<Chat> findByMemberIdAndParentIdIsNullAndDeletedAtIsNull(Long memberId, Pageable pageable);

    // 내 대화 단건 조회 (삭제되지 않은 것)
    Optional<Chat> findByIdAndDeletedAtIsNull(Long chatId);

    // 내 대화 단건 조회 + member fetch join (소유권 검증용)
    @Query("SELECT c FROM Chat c JOIN FETCH c.member WHERE c.id = :chatId AND c.deletedAt IS NULL")
    Optional<Chat> findByIdWithMemberAndDeletedAtIsNull(@Param("chatId") Long chatId);

    // 삭제 여부 무관 단건 조회 (삭제된 chat 검증용)
    Optional<Chat> findById(Long chatId);

    // 자손 chat 조회 (cascade 삭제/복구용)
    List<Chat> findAllByParentId(Long parentId);

    // root 기준 전체 분기 조회 (삭제 안 된 것만)
    List<Chat> findAllByRootChatIdAndDeletedAtIsNull(Long rootChatId);

    // root 기준 전체 분기 조회 (삭제 포함)
    List<Chat> findAllByRootChatId(Long rootChatId);
}
