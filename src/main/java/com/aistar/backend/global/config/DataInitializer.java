package com.aistar.backend.global.config;

import com.aistar.backend.domain.chat.entity.Chat;
import com.aistar.backend.domain.chat.entity.Message;
import com.aistar.backend.domain.chat.entity.Turn;
import com.aistar.backend.domain.chat.enums.LlmModel;
import com.aistar.backend.domain.chat.enums.LlmProvider;
import com.aistar.backend.domain.chat.enums.MessageStatus;
import com.aistar.backend.domain.chat.enums.SenderType;
import com.aistar.backend.domain.chat.repository.ChatRepository;
import com.aistar.backend.domain.chat.repository.MessageRepository;
import com.aistar.backend.domain.chat.repository.TurnRepository;
import com.aistar.backend.domain.member.entity.Member;
import com.aistar.backend.domain.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final MemberRepository memberRepository;
    private final ChatRepository chatRepository;
    private final TurnRepository turnRepository;
    private final MessageRepository messageRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (memberRepository.count() > 0) {
            log.info("데이터가 이미 존재합니다. 초기화를 건너뜁니다.");
            return;
        }

        log.info("더미 데이터 생성 시작");

        // ── Member ──
        Member member = memberRepository.save(Member.builder()
                .email("test@example.com")
                .name("테스트유저")
                .password(passwordEncoder.encode("password123"))
                .build());

        // ── Chat 1: 턴 3개 ──
        Chat chat1 = chatRepository.save(Chat.builder()
                .member(member)
                .title("Spring Boot 질문")
                .llmProvider(LlmProvider.OPENAI)
                .llmModel(LlmModel.GPT_4O_MINI)
                .build());
        chat1.initRootChatId();

        createTurn(chat1, 1,
                "Spring Boot에서 JPA를 사용하는 방법을 알려줘",
                "Spring Boot에서 JPA를 사용하려면 spring-boot-starter-data-jpa 의존성을 추가하고, Entity 클래스와 Repository 인터페이스를 정의하면 됩니다.");

        createTurn(chat1, 2,
                "N+1 문제는 어떻게 해결해?",
                "N+1 문제는 fetch join, @EntityGraph, 또는 batch size 설정으로 해결할 수 있습니다. 상황에 따라 적절한 방법을 선택하세요.");

        createTurn(chat1, 3,
                "QueryDSL은 꼭 써야 해?",
                "필수는 아닙니다. 단순 CRUD는 Spring Data JPA만으로 충분하고, 복잡한 동적 쿼리가 필요할 때 QueryDSL을 도입하면 됩니다.");

        // ── Chat 2: 턴 2개 ──
        Chat chat2 = chatRepository.save(Chat.builder()
                .member(member)
                .title("Git 브랜치 전략")
                .llmProvider(LlmProvider.GOOGLE)
                .llmModel(LlmModel.GEMINI_2_0_FLASH)
                .build());
        chat2.initRootChatId();

        createTurn(chat2, 1,
                "Git Flow와 GitHub Flow의 차이가 뭐야?",
                "Git Flow는 develop/release/hotfix 등 여러 브랜치를 사용하는 구조이고, GitHub Flow는 main + feature 브랜치만 사용하는 단순한 구조입니다.");

        createTurn(chat2, 2,
                "소규모 팀에선 뭐가 좋아?",
                "소규모 팀에서는 GitHub Flow가 적합합니다. 브랜치 관리 오버헤드가 적고, PR 기반 코드 리뷰에 집중할 수 있습니다.");

        // ── Chat 3: 빈 채팅 (턴 없음) ──
        Chat chat3 = chatRepository.save(Chat.builder()
                .member(member)
                .title(null)
                .llmProvider(LlmProvider.ANTHROPIC)
                .llmModel(LlmModel.CLAUDE_3_5_SONNET)
                .build());
        chat3.initRootChatId();

        log.info("더미 데이터 생성 완료 — member 1명, chat 3개, turn 5개");
    }

    private void createTurn(Chat chat, int sequence, String userContent, String aiContent) {
        Turn turn = turnRepository.save(Turn.builder()
                .chat(chat)
                .turnSequence(sequence)
                .summary(aiContent.length() > 50 ? aiContent.substring(0, 50) : aiContent)
                .build());

        messageRepository.save(Message.builder()
                .turn(turn)
                .senderType(SenderType.USER)
                .status(MessageStatus.COMPLETED)
                .content(userContent)
                .build());

        messageRepository.save(Message.builder()
                .turn(turn)
                .senderType(SenderType.ASSISTANT)
                .status(MessageStatus.COMPLETED)
                .content(aiContent)
                .answerToken(aiContent.split("\\s+").length)
                .build());
    }
}
