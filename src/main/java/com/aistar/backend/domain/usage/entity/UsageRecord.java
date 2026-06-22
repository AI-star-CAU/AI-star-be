package com.aistar.backend.domain.usage.entity;

import com.aistar.backend.domain.member.entity.Member;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "usage_record", indexes = {
        @Index(name = "idx_usage_member_period", columnList = "member_id, period_end")
})
public class UsageRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "usage_record_id")
    private Long id;

    @Column(name = "period_start", nullable = false)
    private LocalDateTime periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDateTime periodEnd;

    @Column(name = "tokens_used", nullable = false)
    @Builder.Default
    private Long tokensUsed = 0L;

    @Column(name = "token_limit", nullable = false)
    @Builder.Default
    private Long tokenLimit = 0L;

    @Column(name = "request_count", nullable = false)
    @Builder.Default
    private Integer requestCount = 0;

    @Column(name = "compressed_turn_count", nullable = false)
    @Builder.Default
    private Integer compressedTurnCount = 0;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    public void accumulate(long promptTokens, long answerTokens, int compressedTurns) {
        this.tokensUsed += (promptTokens + answerTokens);
        this.requestCount += 1;
        this.compressedTurnCount += compressedTurns;
    }
}
