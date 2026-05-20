package com.aistar.backend.domain.chat.dto;

import com.aistar.backend.domain.chat.enums.TitleStatus;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;

public class GraphResDto {

    @Builder
    public record GraphResult(
            Long rootChatId,
            CenterDto center,
            List<ChatNodeDto> chats,
            List<TurnNodeDto> turns,
            FrontierDto frontier
    ) {}

    @Builder
    public record CenterDto(
            Long turnId,
            Long chatId
    ) {}

    @Builder
    public record ChatNodeDto(
            Long chatId,
            String title,
            TitleStatus titleStatus,
            Long parentChatId,
            Long branchPointTurnId,
            Integer depth,
            Boolean isDeleted,
            Long lastTurnId,
            LocalDateTime updatedAt
    ) {}

    @Builder
    public record TurnNodeDto(
            Long turnId,
            Long chatId,
            Integer turnSequence,
            String summary,
            String summaryStatus,
            Boolean isBranchPoint,
            Boolean isCurrent,
            LocalDateTime createdAt
    ) {}

    @Builder
    public record FrontierDto(
            List<FrontierPoint> up,
            List<FrontierPoint> down
    ) {}

    @Builder
    public record FrontierPoint(
            Long fromTurnId,
            Boolean hasMore
    ) {}

    @Builder
    public record ExpandResult(
            String direction,
            List<TurnNodeDto> turns,
            FrontierDto frontier
    ) {}
}
