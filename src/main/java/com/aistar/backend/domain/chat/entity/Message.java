package com.aistar.backend.domain.chat.entity;

import com.aistar.backend.domain.chat.enums.MessageStatus;
import com.aistar.backend.domain.chat.enums.SenderType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "message", indexes = {
        @Index(name = "idx_message_turn", columnList = "turn_id")
})
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "message_id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender_type", nullable = false)
    private SenderType senderType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private MessageStatus status = MessageStatus.FAILED;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Column(name = "prompt_token")
    private Integer promptToken;

    @Column(name = "answer_token")
    private Integer answerToken;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "turn_id", nullable = false)
    private Turn turn;

    public void updateStatus(MessageStatus status) {
        this.status = status;
    }

    public void updateContent(String content) {
        this.content = content;
    }

    public void updateAnswerToken(Integer answerToken) {
        this.answerToken = answerToken;
    }

    public void updatePromptToken(Integer promptToken) {
        this.promptToken = promptToken;
    }
}
