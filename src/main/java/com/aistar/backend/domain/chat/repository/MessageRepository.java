package com.aistar.backend.domain.chat.repository;

import com.aistar.backend.domain.chat.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageRepository extends JpaRepository<Message, Long> {
}
