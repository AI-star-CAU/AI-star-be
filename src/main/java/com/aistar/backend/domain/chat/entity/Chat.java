package com.aistar.backend.domain.chat.entity;

import com.aistar.backend.domain.chat.enums.LlmModel;
import com.aistar.backend.domain.chat.enums.LlmProvider;
import com.aistar.backend.domain.member.entity.Member;
import com.aistar.backend.global.entity.BaseEntity;
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
@Table(name = "chat")
public class Chat extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "aichat_session_id")
    private Long id;

    @Column(name = "root_chat_id", nullable = false)
    private Long rootChatId;

    @Column(name = "title", nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private String title = "제목없음";

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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @OneToMany(mappedBy = "chat")
    @Builder.Default
    private List<Turn> turns = new ArrayList<>();

    // Phase 2: save 후 rootChatId를 자기 자신으로 세팅
    public void initRootChatId() {
        if (this.rootChatId == null) {
            this.rootChatId = this.id;
        }
    }

    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
