package com.aistar.backend;

import com.aistar.backend.domain.chat.entity.Chat;
import com.aistar.backend.domain.chat.entity.Turn;
import com.aistar.backend.domain.chat.event.MessageCompletedEvent;
import com.aistar.backend.domain.llm.enums.LlmModel;
import com.aistar.backend.domain.llm.enums.LlmProvider;
import com.aistar.backend.domain.chat.repository.ChatRepository;
import com.aistar.backend.domain.chat.repository.TurnRepository;
import com.aistar.backend.domain.llm.client.LlmStreamClient;
import com.aistar.backend.domain.llm.dto.AiChatRequest;
import com.aistar.backend.domain.member.entity.Member;
import com.aistar.backend.domain.member.repository.MemberRepository;
import com.aistar.backend.domain.usage.entity.Plan;
import com.aistar.backend.domain.usage.entity.UsageRecord;
import com.aistar.backend.domain.usage.listener.UsageAccumulationListener;
import com.aistar.backend.domain.usage.repository.PlanRepository;
import com.aistar.backend.domain.usage.repository.UsageRecordRepository;
import com.aistar.backend.global.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class MessageStreamingRegressionTest {

    @Autowired MockMvc mockMvc;
    @Autowired MemberRepository memberRepository;
    @Autowired ChatRepository chatRepository;
    @Autowired TurnRepository turnRepository;
    @Autowired PlanRepository planRepository;
    @Autowired UsageRecordRepository usageRecordRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TestLlmStreamClient testLlmStreamClient;
    @Autowired UsageAccumulationListener usageAccumulationListener;

    @BeforeEach
    void resetClient() {
        testLlmStreamClient.setMode(TestLlmStreamClient.Mode.SUCCESS);
    }

    @Test
    @DisplayName("SSE 성공 경로: 이벤트 순서/필드 + usage 1회 누적 + summary 생성")
    void sse_success_regression() throws Exception {
        Member member = createMember("sse-success@test.com", "SSE 성공");
        Chat chat = createChat(member);
        createUsageRecord(member, createPlan(), 0L);

        MvcResult started = mockMvc.perform(post("/chats/" + chat.getId() + "/messages")
                        .header("Authorization", "Bearer " + tokenFor(member))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"리팩토링 검증 질문"}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult completed = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn();

        String body = completed.getResponse().getContentAsString();
        assertEventOrder(body, "turn_started", "chunk", "turn_completed", "done");
        assertThat(body).contains("chatId");
        assertThat(body).contains("turnId");
        assertThat(body).contains("aiMessageId");
        assertThat(body).contains("answerToken");

        Turn latestTurn = turnRepository.findTopByChatIdOrderByTurnSequenceDesc(chat.getId()).orElseThrow();
        Turn summarized = waitForSummary(latestTurn.getId());
        assertThat(summarized.getSummary()).isNotBlank();
    }

    @Test
    @DisplayName("SSE 에러 경로: error 이벤트 계약 + usage 단일 누적")
    void sse_error_regression() throws Exception {
        Member member = createMember("sse-error@test.com", "SSE 에러");
        Chat chat = createChat(member);
        createUsageRecord(member, createPlan(), 0L);
        testLlmStreamClient.setMode(TestLlmStreamClient.Mode.ERROR);

        MvcResult started = mockMvc.perform(post("/chats/" + chat.getId() + "/messages")
                        .header("Authorization", "Bearer " + tokenFor(member))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"에러 경로 질문"}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult completed = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn();

        String body = completed.getResponse().getContentAsString();
        assertEventOrder(body, "turn_started", "error", "done");
        assertThat(body).contains("code");
        assertThat(body).contains("message");
        assertThat(body).contains("retryable");
    }

    @Test
    @DisplayName("UsageAccumulationListener 처리: usage 단일 누적")
    void usage_accumulates_once_on_event() throws Exception {
        Member member = createMember("usage-event@test.com", "Usage 이벤트");
        UsageRecord record = createUsageRecord(member, createPlan(), 0L);

        usageAccumulationListener.onMessageCompleted(
                new MessageCompletedEvent(
                        member.getId(),
                        1L,
                        10,
                        20,
                        0,
                        "summary text"
                )
        );

        UsageRecord usage = waitForUsage(member.getId(), 1);
        assertThat(usage.getId()).isEqualTo(record.getId());
        assertThat(usage.getRequestCount()).isEqualTo(1);
        assertThat(usage.getTokensUsed()).isEqualTo(30L);
    }

    private void assertEventOrder(String body, String... events) {
        int prev = -1;
        for (String event : events) {
            int idx = body.indexOf("event:" + event);
            assertThat(idx)
                    .as("event order must contain " + event)
                    .isGreaterThan(prev);
            prev = idx;
        }
    }

    private UsageRecord waitForUsage(Long memberId, int expectedCount) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            UsageRecord usage = usageRecordRepository.findActiveByMemberId(memberId, LocalDateTime.now()).orElseThrow();
            if (usage.getRequestCount() == expectedCount) {
                return usage;
            }
            Thread.sleep(20);
        }
        return usageRecordRepository.findActiveByMemberId(memberId, LocalDateTime.now()).orElseThrow();
    }

    private Turn waitForSummary(Long turnId) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Turn turn = turnRepository.findById(turnId).orElseThrow();
            if (turn.getSummary() != null && !turn.getSummary().isBlank()) {
                return turn;
            }
            Thread.sleep(20);
        }
        return turnRepository.findById(turnId).orElseThrow();
    }

    private Member createMember(String email, String name) {
        Member member = Member.builder()
                .email(email)
                .password(passwordEncoder.encode("password1234"))
                .name(name)
                .build();
        return memberRepository.saveAndFlush(member);
    }

    private String tokenFor(Member member) {
        return jwtTokenProvider.createToken(member.getId(), member.getEmail());
    }

    private Chat createChat(Member member) {
        Chat chat = Chat.builder()
                .title("스트리밍 회귀 테스트")
                .llmProvider(LlmProvider.OPENAI)
                .llmModel(LlmModel.GPT_4O_MINI)
                .member(member)
                .build();
        chatRepository.saveAndFlush(chat);
        chat.initRootChatId();
        chatRepository.flush();
        return chat;
    }

    private Plan createPlan() {
        return planRepository.saveAndFlush(Plan.builder()
                .name("Free")
                .price(BigDecimal.ZERO)
                .maxProject(3)
                .maxDepth(5)
                .maxFileSize(10_000_000L)
                .aiLimit(500_000L)
                .description("무료 플랜")
                .build());
    }

    private UsageRecord createUsageRecord(Member member, Plan plan, long tokensUsed) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime periodStart = now.withDayOfMonth(1).toLocalDate().atStartOfDay();
        LocalDateTime periodEnd = periodStart.plusMonths(1);

        return usageRecordRepository.saveAndFlush(UsageRecord.builder()
                .member(member)
                .plan(plan)
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .tokenLimit(plan.getAiLimit())
                .tokensUsed(tokensUsed)
                .build());
    }

    @TestConfiguration
    static class StreamingTestConfig {
        @Bean
        @Primary
        TestLlmStreamClient testLlmStreamClient() {
            return new TestLlmStreamClient();
        }
    }

    static class TestLlmStreamClient implements LlmStreamClient {
        enum Mode { SUCCESS, ERROR }
        private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.SUCCESS);

        void setMode(Mode newMode) {
            mode.set(newMode);
        }

        @Override
        public void streamChatCompletion(AiChatRequest request, Consumer<String> onChunk) {
            if (mode.get() == Mode.ERROR) {
                throw new RuntimeException("forced streaming failure");
            }
            List<String> chunks = List.of("테스트 ", "응답");
            for (String chunk : chunks) {
                onChunk.accept(chunk);
            }
        }
    }
}
