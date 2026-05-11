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
@Table(name = "turn")
public class Turn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "turn_id")
    private Long id;

    @Column(name = "turn_sequence", nullable = false)
    private Integer turnSequence;

    @Column(name = "summary", columnDefinition = "TEXT")
    @Builder.Default
    private String summary = "제목없음";

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "aichat_session_id", nullable = false)
    private Chat chat;

    @OneToMany(mappedBy = "turn")
    @Builder.Default
    private List<Message> messages = new ArrayList<>();

    public void updateSummary(String summary) {
        this.summary = summary;
    }
}
