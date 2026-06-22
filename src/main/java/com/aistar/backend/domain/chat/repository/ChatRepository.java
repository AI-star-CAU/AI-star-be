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

    // ── Explorer: root 페이징 (recent 정렬) ──
    @Query("SELECT c FROM Chat c WHERE c.member.id = :memberId AND c.parentId IS NULL " +
            "AND (:includeDeleted = true OR c.deletedAt IS NULL) " +
            "ORDER BY c.lastActivityAt DESC, c.id DESC")
    Page<Chat> findRootsByRecent(@Param("memberId") Long memberId,
                                 @Param("includeDeleted") boolean includeDeleted,
                                 Pageable pageable);

    // ── Explorer: root 페이징 (created 정렬) ──
    @Query("SELECT c FROM Chat c WHERE c.member.id = :memberId AND c.parentId IS NULL " +
            "AND (:includeDeleted = true OR c.deletedAt IS NULL) " +
            "ORDER BY c.createdAt DESC, c.id DESC")
    Page<Chat> findRootsByCreated(@Param("memberId") Long memberId,
                                  @Param("includeDeleted") boolean includeDeleted,
                                  Pageable pageable);

    // ── Explorer: root 페이징 (name 정렬, title NULL은 마지막) ──
    @Query("SELECT c FROM Chat c WHERE c.member.id = :memberId AND c.parentId IS NULL " +
            "AND (:includeDeleted = true OR c.deletedAt IS NULL) " +
            "ORDER BY CASE WHEN c.title IS NULL THEN 1 ELSE 0 END ASC, " +
            "LOWER(c.title) ASC, c.id DESC")
    Page<Chat> findRootsByName(@Param("memberId") Long memberId,
                               @Param("includeDeleted") boolean includeDeleted,
                               Pageable pageable);

    // ── Explorer: root 목록의 전체 하위 chat 배치 조회 ──
    @Query("SELECT c FROM Chat c WHERE c.rootChatId IN :rootChatIds " +
            "AND (:includeDeleted = true OR c.deletedAt IS NULL)")
    List<Chat> findAllByRootChatIdIn(@Param("rootChatIds") List<Long> rootChatIds,
                                     @Param("includeDeleted") boolean includeDeleted);
}
