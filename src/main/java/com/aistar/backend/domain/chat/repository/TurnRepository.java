package com.aistar.backend.domain.chat.repository;

import com.aistar.backend.domain.chat.entity.Turn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TurnRepository extends JpaRepository<Turn, Long> {

    @Query("SELECT t.chat.id, COUNT(t) FROM Turn t WHERE t.chat.id IN :chatIds GROUP BY t.chat.id")
    List<Object[]> countByChatIds(@Param("chatIds") List<Long> chatIds);

    // 해당 chat의 최대 turnSequence 조회 (새 턴 생성 시 +1 용)
    Optional<Turn> findTopByChatIdOrderByTurnSequenceDesc(Long chatId);

    // cursor 기반 페이징: BACKWARD (과거로)
    List<Turn> findByChatIdAndTurnSequenceLessThanOrderByTurnSequenceDesc(
            Long chatId, int turnSequence, org.springframework.data.domain.Pageable pageable);

    // cursor 기반 페이징: 첫 호출 (최신부터)
    List<Turn> findByChatIdOrderByTurnSequenceDesc(
            Long chatId, org.springframework.data.domain.Pageable pageable);

    // cursor 기반 페이징: FORWARD (미래로)
    List<Turn> findByChatIdAndTurnSequenceGreaterThanOrderByTurnSequenceAsc(
            Long chatId, int turnSequence, org.springframework.data.domain.Pageable pageable);

    // 특정 turn 단건 조회
    Optional<Turn> findByIdAndChatId(Long turnId, Long chatId);

    // frontier 존재 여부 확인
    boolean existsByChatIdAndTurnSequenceGreaterThan(Long chatId, int turnSequence);
}
