package com.aistar.backend.domain.chat.entity;

import com.aistar.backend.domain.chat.enums.LlmModel;
import com.aistar.backend.domain.chat.enums.LlmProvider;
import com.aistar.backend.domain.member.entity.Member;
import com.aistar.backend.global.entity.BaseEntity;
import com.aistar.backend.domain.chat.enums.TitleStatus;
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
@Table(name = "chat", indexes = {
        @Index(name = "idx_chat_root_deleted", columnList = "root_chat_id, deleted_at"),
        @Index(name = "idx_chat_parent", columnList = "parent_id"),
        @Index(name = "idx_chat_branch_point", columnList = "branch_point_turn_id"),
        @Index(name = "idx_chat_member_last_activity", columnList = "member_id, last_activity_at")
})
public class Chat extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "chat_id")
    private Long id;

    @Column(name = "root_chat_id")
    private Long rootChatId;

    @Column(name = "title", columnDefinition = "TEXT")
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "title_status", nullable = false)
    @Builder.Default
    private TitleStatus titleStatus = TitleStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "llm_provider", nullable = false)
    private LlmProvider llmProvider;

    @Enumerated(EnumType.STRING)
    @Column(name = "llm_model", nullable = false)
    private LlmModel llmModel;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "branch_point_turn_id")
    private Long branchPointTurnId;

    @Column(name = "last_turn_id")
    private Long lastTurnId;

    @Column(name = "last_activity_at", nullable = false,
            columnDefinition = "timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP")
    @Builder.Default
    private LocalDateTime lastActivityAt = LocalDateTime.now();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @OneToMany(mappedBy = "chat")
    @Builder.Default
    private List<Turn> turns = new ArrayList<>();

    public void initRootChatId() {
        if (this.rootChatId == null) {
            this.rootChatId = this.id;
        }
    }

    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    public void touchUpdatedAt() {
        setUpdatedAt(LocalDateTime.now());
    }

    public void updateLastTurnId(Long turnId) {
        this.lastTurnId = turnId;
    }

    public void updateTitle(String title) {
        this.title = title;
        this.titleStatus = TitleStatus.USER_EDITED;
    }

    public void updateGeneratedTitle(String title) {
        this.title = title;
        this.titleStatus = TitleStatus.GENERATED;
    }

    public void touchLastActivityAt() {
        this.lastActivityAt = LocalDateTime.now();
    }
}
