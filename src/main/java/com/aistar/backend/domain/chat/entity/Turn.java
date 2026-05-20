package com.aistar.backend.domain.chat.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "turn", indexes = {
        @Index(name = "idx_turn_chat_sequence", columnList = "aichat_session_id, turn_sequence")
})
public class Turn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "turn_id")
    private Long id;

    @Column(name = "turn_sequence", nullable = false)
    private Integer turnSequence;

    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "aichat_session_id", nullable = false)
    private Chat chat;

    @OneToMany(mappedBy = "turn")
    @OrderBy("id ASC")
    @Builder.Default
    private List<Message> messages = new ArrayList<>();

    public void updateSummary(String summary) {
        this.summary = summary;
    }
}
