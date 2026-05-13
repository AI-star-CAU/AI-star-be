package com.aistar.backend.domain.chat.repository;

import com.aistar.backend.domain.chat.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {

    @Query("SELECT m FROM Message m WHERE m.id IN " +
           "(SELECT MAX(m2.id) FROM Message m2 WHERE m2.turn.chat.id IN :chatIds GROUP BY m2.turn.chat.id)")
    List<Message> findLatestByChatIds(@Param("chatIds") List<Long> chatIds);
}
