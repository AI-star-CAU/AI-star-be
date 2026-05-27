package com.aistar.backend;

import com.aistar.backend.domain.chat.entity.Chat;
import com.aistar.backend.domain.chat.entity.Message;
import com.aistar.backend.domain.chat.entity.Turn;
import com.aistar.backend.domain.chat.enums.*;
import com.aistar.backend.domain.chat.repository.ChatRepository;
import com.aistar.backend.domain.chat.repository.MessageRepository;
import com.aistar.backend.domain.chat.repository.TurnRepository;
import com.aistar.backend.domain.member.entity.Member;
import com.aistar.backend.domain.member.repository.MemberRepository;
import com.aistar.backend.domain.usage.entity.Plan;
import com.aistar.backend.domain.usage.entity.UsageRecord;
import com.aistar.backend.domain.usage.repository.PlanRepository;
import com.aistar.backend.domain.usage.repository.UsageRecordRepository;
import com.aistar.backend.global.security.JwtTokenProvider;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class Phase4ApiTest {

    @Autowired MockMvc mockMvc;
    @Autowired MemberRepository memberRepository;
    @Autowired ChatRepository chatRepository;
    @Autowired TurnRepository turnRepository;
    @Autowired MessageRepository messageRepository;
    @Autowired PlanRepository planRepository;
    @Autowired UsageRecordRepository usageRecordRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired EntityManager em;

    // ── Helper ──

    private Member createMember(String email) {
        Member member = Member.builder()
                .email(email)
                .password(passwordEncoder.encode("password1234"))
                .name("테스터")
                .build();
        memberRepository.saveAndFlush(member);
        return member;
    }

    private String tokenFor(Member member) {
        return jwtTokenProvider.createToken(member.getId(), member.getEmail());
    }

    private Chat createChat(Member member) {
        Chat chat = Chat.builder()
                .title("테스트 대화")
                .llmProvider(LlmProvider.LOCAL)
                .llmModel(LlmModel.LOCAL_DEFAULT)
                .member(member)
                .build();
        chatRepository.saveAndFlush(chat);
        chat.initRootChatId();
        chatRepository.flush();
        return chat;
    }

    private Chat createChatWithTitle(Member member, String title) {
        Chat chat = Chat.builder()
                .title(title)
                .llmProvider(LlmProvider.LOCAL)
                .llmModel(LlmModel.LOCAL_DEFAULT)
                .member(member)
                .build();
        chatRepository.saveAndFlush(chat);
        chat.initRootChatId();
        chatRepository.flush();
        return chat;
    }

    private Turn createCompletedTurn(Chat chat, int sequence, String userContent, String aiContent) {
        Turn turn = turnRepository.saveAndFlush(Turn.builder()
                .chat(chat)
                .turnSequence(sequence)
                .summary(aiContent.length() > 50 ? aiContent.substring(0, 50) : aiContent)
                .build());

        messageRepository.saveAndFlush(Message.builder()
                .turn(turn)
                .senderType(SenderType.USER)
                .status(MessageStatus.COMPLETED)
                .content(userContent)
                .build());

        messageRepository.saveAndFlush(Message.builder()
                .turn(turn)
                .senderType(SenderType.ASSISTANT)
                .status(MessageStatus.COMPLETED)
                .content(aiContent)
                .answerToken(aiContent.split("\\s+").length)
                .build());

        chat.updateLastTurnId(turn.getId());
        chatRepository.flush();
        return turn;
    }

    private Chat createBranch(Chat parent, Turn branchPointTurn) {
        Chat branch = Chat.builder()
                .title(null)
                .titleStatus(TitleStatus.PENDING)
                .llmProvider(parent.getLlmProvider())
                .llmModel(parent.getLlmModel())
                .member(parent.getMember())
                .parentId(parent.getId())
                .branchPointTurnId(branchPointTurn.getId())
                .rootChatId(parent.getRootChatId())
                .build();
        chatRepository.saveAndFlush(branch);
        return branch;
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

    // ── 1. Explorer 탐색기 트리 조회 ──

    @Nested
    @DisplayName("GET /chats/explorer")
    class ExplorerPage {

        @Test
        @DisplayName("기본 조회 — root chat 목록과 트리 구조 반환")
        void success() throws Exception {
            Member member = createMember("explorer@test.com");
            Chat root = createChat(member);
            createCompletedTurn(root, 1, "질문1", "답변1");
            em.flush();
            em.clear();

            mockMvc.perform(get("/chats/explorer")
                            .header("Authorization", "Bearer " + tokenFor(member)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.roots.length()").value(1))
                    .andExpect(jsonPath("$.result.roots[0].rootChatId").value(root.getId()))
                    .andExpect(jsonPath("$.result.roots[0].nodes.length()").value(1))
                    .andExpect(jsonPath("$.result.roots[0].nodes[0].turnCount").value(1))
                    .andExpect(jsonPath("$.result.roots[0].nodes[0].depth").value(0))
                    .andExpect(jsonPath("$.result.totalRootCount").value(1))
                    .andExpect(jsonPath("$.result.hasNext").value(false));
        }

        @Test
        @DisplayName("분기 포함 트리 — depth, parentChatId 확인")
        void withBranch() throws Exception {
            Member member = createMember("explBranch@test.com");
            Chat root = createChat(member);
            Turn turn1 = createCompletedTurn(root, 1, "질문", "답변");
            Chat branch = createBranch(root, turn1);
            createCompletedTurn(branch, 1, "분기 질문", "분기 답변");
            em.flush();
            em.clear();

            mockMvc.perform(get("/chats/explorer")
                            .header("Authorization", "Bearer " + tokenFor(member)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.roots[0].nodes.length()").value(2))
                    .andExpect(jsonPath("$.result.roots[0].nodes[1].depth").value(1))
                    .andExpect(jsonPath("$.result.roots[0].nodes[1].parentChatId").value(root.getId()));
        }

        @Test
        @DisplayName("빈 결과 — 대화가 없으면 빈 배열")
        void empty() throws Exception {
            Member member = createMember("explEmpty@test.com");

            mockMvc.perform(get("/chats/explorer")
                            .header("Authorization", "Bearer " + tokenFor(member)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.roots.length()").value(0))
                    .andExpect(jsonPath("$.result.totalRootCount").value(0))
                    .andExpect(jsonPath("$.result.hasNext").value(false));
        }

        @Test
        @DisplayName("페이지네이션 — size=1, hasNext 확인")
        void pagination() throws Exception {
            Member member = createMember("explPage@test.com");
            createChat(member);
            createChat(member);
            em.flush();
            em.clear();

            mockMvc.perform(get("/chats/explorer")
                            .param("size", "1")
                            .header("Authorization", "Bearer " + tokenFor(member)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.roots.length()").value(1))
                    .andExpect(jsonPath("$.result.totalRootCount").value(2))
                    .andExpect(jsonPath("$.result.hasNext").value(true));
        }

        @Test
        @DisplayName("정렬 — name 정렬 시 제목 알파벳순")
        void sortByName() throws Exception {
            Member member = createMember("explSort@test.com");
            createChatWithTitle(member, "BBB 대화");
            createChatWithTitle(member, "AAA 대화");
            em.flush();
            em.clear();

            mockMvc.perform(get("/chats/explorer")
                            .param("sort", "name")
                            .header("Authorization", "Bearer " + tokenFor(member)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.roots[0].nodes[0].title").value("AAA 대화"))
                    .andExpect(jsonPath("$.result.roots[1].nodes[0].title").value("BBB 대화"));
        }

        @Test
        @DisplayName("잘못된 size — 400")
        void invalidSize() throws Exception {
            Member member = createMember("explSize@test.com");

            mockMvc.perform(get("/chats/explorer")
                            .param("size", "51")
                            .header("Authorization", "Bearer " + tokenFor(member)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("잘못된 sort — 400")
        void invalidSort() throws Exception {
            Member member = createMember("explInvSort@test.com");

            mockMvc.perform(get("/chats/explorer")
                            .param("sort", "invalid")
                            .header("Authorization", "Bearer " + tokenFor(member)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("삭제된 chat 제외 — includeDeleted=false")
        void excludeDeleted() throws Exception {
            Member member = createMember("explDel@test.com");
            Chat root = createChat(member);
            root.softDelete();
            chatRepository.flush();
            em.flush();
            em.clear();

            mockMvc.perform(get("/chats/explorer")
                            .header("Authorization", "Bearer " + tokenFor(member)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.roots.length()").value(0));
        }

        @Test
        @DisplayName("삭제된 chat 포함 — includeDeleted=true")
        void includeDeleted() throws Exception {
            Member member = createMember("explDelInc@test.com");
            Chat root = createChat(member);
            root.softDelete();
            chatRepository.flush();
            em.flush();
            em.clear();

            mockMvc.perform(get("/chats/explorer")
                            .param("includeDeleted", "true")
                            .header("Authorization", "Bearer " + tokenFor(member)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.roots.length()").value(1))
                    .andExpect(jsonPath("$.result.roots[0].nodes[0].deletedAt").isNotEmpty());
        }
    }

    // ── 2. Explorer 단일 트리 새로고침 ──

    @Nested
    @DisplayName("GET /chats/explorer/{rootChatId}")
    class ExplorerTree {

        @Test
        @DisplayName("단일 트리 조회 성공")
        void success() throws Exception {
            Member member = createMember("tree@test.com");
            Chat root = createChat(member);
            Turn turn1 = createCompletedTurn(root, 1, "질문", "답변");
            Chat branch = createBranch(root, turn1);
            em.flush();
            em.clear();

            mockMvc.perform(get("/chats/explorer/" + root.getId())
                            .header("Authorization", "Bearer " + tokenFor(member)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.rootChatId").value(root.getId()))
                    .andExpect(jsonPath("$.result.nodes.length()").value(2))
                    .andExpect(jsonPath("$.result.nodes[0].depth").value(0))
                    .andExpect(jsonPath("$.result.nodes[1].depth").value(1));
        }

        @Test
        @DisplayName("존재하지 않는 rootChatId — 404")
        void notFound() throws Exception {
            Member member = createMember("treeNF@test.com");

            mockMvc.perform(get("/chats/explorer/999999")
                            .header("Authorization", "Bearer " + tokenFor(member)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("다른 사용자의 chat — 403")
        void otherUser() throws Exception {
            Member owner = createMember("treeOwner@test.com");
            Member other = createMember("treeOther@test.com");
            Chat root = createChat(owner);
            em.flush();
            em.clear();

            mockMvc.perform(get("/chats/explorer/" + root.getId())
                            .header("Authorization", "Bearer " + tokenFor(other)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("root가 아닌 chat 조회 — 400")
        void notRootChat() throws Exception {
            Member member = createMember("treeNotRoot@test.com");
            Chat root = createChat(member);
            Turn turn1 = createCompletedTurn(root, 1, "질문", "답변");
            Chat branch = createBranch(root, turn1);
            em.flush();
            em.clear();

            mockMvc.perform(get("/chats/explorer/" + branch.getId())
                            .header("Authorization", "Bearer " + tokenFor(member)))
                    .andExpect(status().isBadRequest());
        }
    }

    // ── 3. Usage 사용량 조회 ──

    @Nested
    @DisplayName("GET /usage/me")
    class UsageMe {

        @Test
        @DisplayName("활성 record 조회 성공")
        void success() throws Exception {
            Member member = createMember("usage@test.com");
            Plan plan = createPlan();
            createUsageRecord(member, plan, 10_000L);
            em.flush();
            em.clear();

            mockMvc.perform(get("/usage/me")
                            .header("Authorization", "Bearer " + tokenFor(member)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.planName").value("Free"))
                    .andExpect(jsonPath("$.result.tokensUsed").value(10_000))
                    .andExpect(jsonPath("$.result.tokenLimit").value(500_000))
                    .andExpect(jsonPath("$.result.remainingTokens").value(490_000))
                    .andExpect(jsonPath("$.result.warningLevel").value("NONE"));
        }

        @Test
        @DisplayName("usageRatio 계산 확인")
        void usageRatio() throws Exception {
            Member member = createMember("usageRatio@test.com");
            Plan plan = createPlan();
            createUsageRecord(member, plan, 250_000L);
            em.flush();
            em.clear();

            mockMvc.perform(get("/usage/me")
                            .header("Authorization", "Bearer " + tokenFor(member)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.usageRatio").value(0.5))
                    .andExpect(jsonPath("$.result.warningLevel").value("NONE"));
        }

        @Test
        @DisplayName("WARN 경고 — 사용률 80% 이상")
        void warnLevel() throws Exception {
            Member member = createMember("usageWarn@test.com");
            Plan plan = createPlan();
            createUsageRecord(member, plan, 400_000L);
            em.flush();
            em.clear();

            mockMvc.perform(get("/usage/me")
                            .header("Authorization", "Bearer " + tokenFor(member)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.warningLevel").value("WARN"));
        }

        @Test
        @DisplayName("CRITICAL 경고 — 사용률 95% 이상")
        void criticalLevel() throws Exception {
            Member member = createMember("usageCrit@test.com");
            Plan plan = createPlan();
            createUsageRecord(member, plan, 480_000L);
            em.flush();
            em.clear();

            mockMvc.perform(get("/usage/me")
                            .header("Authorization", "Bearer " + tokenFor(member)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.warningLevel").value("CRITICAL"));
        }

        @Test
        @DisplayName("만료된 record — 새 기간 자동 생성")
        void expiredRecord_createsNewPeriod() throws Exception {
            Member member = createMember("usageExpired@test.com");
            Plan plan = createPlan();

            // 지난 달 record (만료됨)
            LocalDateTime lastMonth = LocalDateTime.now().minusMonths(1);
            LocalDateTime periodStart = lastMonth.withDayOfMonth(1).toLocalDate().atStartOfDay();
            LocalDateTime periodEnd = periodStart.plusMonths(1);
            usageRecordRepository.saveAndFlush(UsageRecord.builder()
                    .member(member)
                    .plan(plan)
                    .periodStart(periodStart)
                    .periodEnd(periodEnd)
                    .tokenLimit(plan.getAiLimit())
                    .tokensUsed(100_000L)
                    .build());
            em.flush();
            em.clear();

            mockMvc.perform(get("/usage/me")
                            .header("Authorization", "Bearer " + tokenFor(member)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.tokensUsed").value(0))
                    .andExpect(jsonPath("$.result.tokenLimit").value(500_000))
                    .andExpect(jsonPath("$.result.planName").value("Free"));
        }

        @Test
        @DisplayName("record 없음 — 404")
        void noRecord() throws Exception {
            Member member = createMember("usageNone@test.com");

            mockMvc.perform(get("/usage/me")
                            .header("Authorization", "Bearer " + tokenFor(member)))
                    .andExpect(status().isNotFound());
        }
    }

    // ── 4. UsageRecord accumulate ──

    @Nested
    @DisplayName("UsageRecord.accumulate")
    class Accumulate {

        @Test
        @DisplayName("토큰 누적 — tokensUsed, requestCount 증가")
        void accumulateTokens() {
            Member member = createMember("accum@test.com");
            Plan plan = createPlan();
            UsageRecord record = createUsageRecord(member, plan, 0L);

            record.accumulate(100, 200, 1);
            em.flush();
            em.clear();

            UsageRecord updated = usageRecordRepository.findById(record.getId()).orElseThrow();
            assertThat(updated.getTokensUsed()).isEqualTo(300L);
            assertThat(updated.getRequestCount()).isEqualTo(1);
            assertThat(updated.getCompressedTurnCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("연속 누적 — 여러 번 호출 시 합산")
        void multipleAccumulate() {
            Member member = createMember("accumMulti@test.com");
            Plan plan = createPlan();
            UsageRecord record = createUsageRecord(member, plan, 0L);

            record.accumulate(100, 200, 0);
            record.accumulate(50, 150, 2);
            em.flush();
            em.clear();

            UsageRecord updated = usageRecordRepository.findById(record.getId()).orElseThrow();
            assertThat(updated.getTokensUsed()).isEqualTo(500L);
            assertThat(updated.getRequestCount()).isEqualTo(2);
            assertThat(updated.getCompressedTurnCount()).isEqualTo(2);
        }
    }

    // ── 5. lastActivityAt 전파 ──

    @Nested
    @DisplayName("lastActivityAt 갱신")
    class LastActivityAt {

        @Test
        @DisplayName("분기 생성 시 ancestor chain lastActivityAt 갱신")
        void branchCreation_updatesAncestor() throws Exception {
            Member member = createMember("activity@test.com");
            Chat root = createChat(member);
            Turn turn = createCompletedTurn(root, 1, "질문", "답변");
            em.flush();
            em.clear();

            Chat rootBefore = chatRepository.findById(root.getId()).orElseThrow();
            var beforeTime = rootBefore.getLastActivityAt();
            Thread.sleep(10);

            mockMvc.perform(post("/chats/" + root.getId() + "/branches")
                            .header("Authorization", "Bearer " + tokenFor(member))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"branchPointTurnId":%d}
                                    """.formatted(turn.getId())))
                    .andExpect(status().isCreated());

            em.flush();
            em.clear();

            Chat rootAfter = chatRepository.findById(root.getId()).orElseThrow();
            assertThat(rootAfter.getLastActivityAt()).isAfterOrEqualTo(beforeTime);
        }

        @Test
        @DisplayName("제목 수정 시 ancestor chain lastActivityAt 갱신")
        void titleUpdate_updatesAncestor() throws Exception {
            Member member = createMember("actTitle@test.com");
            Chat root = createChat(member);
            Turn turn = createCompletedTurn(root, 1, "질문", "답변");
            Chat branch = createBranch(root, turn);
            em.flush();
            em.clear();

            Chat rootBefore = chatRepository.findById(root.getId()).orElseThrow();
            var beforeTime = rootBefore.getLastActivityAt();
            Thread.sleep(10);

            mockMvc.perform(patch("/chats/" + branch.getId())
                            .header("Authorization", "Bearer " + tokenFor(member))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"title":"새 제목"}
                                    """))
                    .andExpect(status().isOk());

            em.flush();
            em.clear();

            Chat rootAfter = chatRepository.findById(root.getId()).orElseThrow();
            assertThat(rootAfter.getLastActivityAt()).isAfterOrEqualTo(beforeTime);
        }
    }

    // ── 6. Explorer turnCount 정확성 ──

    @Nested
    @DisplayName("Explorer turnCount")
    class ExplorerTurnCount {

        @Test
        @DisplayName("turn이 여러 개인 chat의 turnCount 정확 반영")
        void multipleTurns() throws Exception {
            Member member = createMember("turnCount@test.com");
            Chat root = createChat(member);
            createCompletedTurn(root, 1, "질문1", "답변1");
            createCompletedTurn(root, 2, "질문2", "답변2");
            createCompletedTurn(root, 3, "질문3", "답변3");
            em.flush();
            em.clear();

            mockMvc.perform(get("/chats/explorer")
                            .header("Authorization", "Bearer " + tokenFor(member)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.roots[0].nodes[0].turnCount").value(3));
        }

        @Test
        @DisplayName("turn이 없는 chat의 turnCount는 0")
        void zeroTurns() throws Exception {
            Member member = createMember("noTurn@test.com");
            createChat(member);
            em.flush();
            em.clear();

            mockMvc.perform(get("/chats/explorer")
                            .header("Authorization", "Bearer " + tokenFor(member)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.roots[0].nodes[0].turnCount").value(0));
        }
    }
}
