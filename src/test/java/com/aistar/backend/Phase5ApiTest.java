package com.aistar.backend;

import com.aistar.backend.domain.chat.entity.Chat;
import com.aistar.backend.domain.chat.entity.Message;
import com.aistar.backend.domain.chat.entity.Turn;
import com.aistar.backend.domain.chat.enums.MessageStatus;
import com.aistar.backend.domain.chat.enums.SenderType;
import com.aistar.backend.domain.chat.enums.TitleStatus;
import com.aistar.backend.domain.llm.enums.LlmModel;
import com.aistar.backend.domain.llm.enums.LlmProvider;
import com.aistar.backend.domain.chat.repository.ChatRepository;
import com.aistar.backend.domain.chat.repository.MessageRepository;
import com.aistar.backend.domain.chat.repository.TurnRepository;
import com.aistar.backend.domain.chat.service.ContextAssembler;
import com.aistar.backend.domain.member.entity.Member;
import com.aistar.backend.domain.member.repository.MemberRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class Phase5ApiTest {

    @Autowired MockMvc mockMvc;
    @Autowired MemberRepository memberRepository;
    @Autowired ChatRepository chatRepository;
    @Autowired TurnRepository turnRepository;
    @Autowired MessageRepository messageRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired ContextAssembler contextAssembler;
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

    private Chat createChat(Member member, LlmProvider provider, LlmModel model) {
        Chat chat = Chat.builder()
                .title("테스트 대화")
                .llmProvider(provider)
                .llmModel(model)
                .member(member)
                .build();
        chatRepository.saveAndFlush(chat);
        chat.initRootChatId();
        chatRepository.flush();
        return chat;
    }

    private Chat createChat(Member member) {
        return createChat(member, LlmProvider.LOCAL, LlmModel.LOCAL_DEFAULT);
    }

    private Chat createChatPending(Member member) {
        Chat chat = Chat.builder()
                .title(null)
                .titleStatus(TitleStatus.PENDING)
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

    // ── 1. LOCAL LLM 기본 지원 ──

    @Nested
    @DisplayName("LOCAL LLM 기본 지원")
    class LocalLlmDefault {

        @Test
        @DisplayName("provider/model 미지정 시 LOCAL 자동 적용")
        void createChat_defaultLocal() throws Exception {
            Member member = createMember("local1@test.com");

            mockMvc.perform(post("/chats")
                            .header("Authorization", "Bearer " + tokenFor(member))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"title":"제목만 지정"}
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.result.llmProvider").value("LOCAL"))
                    .andExpect(jsonPath("$.result.llmModel").value("local-default"));
        }

        @Test
        @DisplayName("provider/model 명시 지정 — 지정된 값 사용")
        void createChat_explicitProvider() throws Exception {
            Member member = createMember("local2@test.com");

            mockMvc.perform(post("/chats")
                            .header("Authorization", "Bearer " + tokenFor(member))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"title":"GPT 대화","llmProvider":"OPENAI","llmModel":"gpt-4o-mini"}
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.result.llmProvider").value("OPENAI"))
                    .andExpect(jsonPath("$.result.llmModel").value("gpt-4o-mini"));
        }

        @Test
        @DisplayName("title도 미지정 — 전부 기본값 적용")
        void createChat_allDefaults() throws Exception {
            Member member = createMember("local3@test.com");

            mockMvc.perform(post("/chats")
                            .header("Authorization", "Bearer " + tokenFor(member))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.result.llmProvider").value("LOCAL"))
                    .andExpect(jsonPath("$.result.llmModel").value("local-default"))
                    .andExpect(jsonPath("$.result.title").isEmpty())
                    .andExpect(jsonPath("$.result.titleStatus").value("PENDING"));
        }

        @Test
        @DisplayName("분기 생성 시 부모의 provider/model 상속")
        void branch_inheritsParent() throws Exception {
            Member member = createMember("local4@test.com");
            Chat root = createChat(member);
            Turn turn = createCompletedTurn(root, 1, "질문", "답변");
            em.flush();
            em.clear();

            mockMvc.perform(post("/chats/" + root.getId() + "/branches")
                            .header("Authorization", "Bearer " + tokenFor(member))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"branchPointTurnId":%d}
                                    """.formatted(turn.getId())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.result.llmProvider").value("LOCAL"))
                    .andExpect(jsonPath("$.result.llmModel").value("local-default"));
        }
    }

    // ── 2. Chat.updateGeneratedTitle ──

    @Nested
    @DisplayName("Chat 제목 엔티티 동작")
    class ChatTitleEntity {

        @Test
        @DisplayName("updateGeneratedTitle — GENERATED 상태로 변경")
        void updateGeneratedTitle() {
            Member member = createMember("genTitle@test.com");
            Chat chat = createChatPending(member);
            assertThat(chat.getTitleStatus()).isEqualTo(TitleStatus.PENDING);
            assertThat(chat.getTitle()).isNull();

            chat.updateGeneratedTitle("AI가 생성한 제목");
            chatRepository.flush();
            em.flush();
            em.clear();

            Chat updated = chatRepository.findById(chat.getId()).orElseThrow();
            assertThat(updated.getTitle()).isEqualTo("AI가 생성한 제목");
            assertThat(updated.getTitleStatus()).isEqualTo(TitleStatus.GENERATED);
        }

        @Test
        @DisplayName("updateTitle — USER_EDITED 상태로 변경")
        void updateTitle_userEdited() {
            Member member = createMember("userTitle@test.com");
            Chat chat = createChatPending(member);

            chat.updateTitle("사용자가 수정한 제목");
            chatRepository.flush();
            em.flush();
            em.clear();

            Chat updated = chatRepository.findById(chat.getId()).orElseThrow();
            assertThat(updated.getTitle()).isEqualTo("사용자가 수정한 제목");
            assertThat(updated.getTitleStatus()).isEqualTo(TitleStatus.USER_EDITED);
        }

        @Test
        @DisplayName("GENERATED 후 사용자 수정 — USER_EDITED로 덮어쓰기")
        void generatedThenUserEdited() {
            Member member = createMember("overwrite@test.com");
            Chat chat = createChatPending(member);

            chat.updateGeneratedTitle("AI 제목");
            assertThat(chat.getTitleStatus()).isEqualTo(TitleStatus.GENERATED);

            chat.updateTitle("내가 바꾼 제목");
            chatRepository.flush();
            em.flush();
            em.clear();

            Chat updated = chatRepository.findById(chat.getId()).orElseThrow();
            assertThat(updated.getTitle()).isEqualTo("내가 바꾼 제목");
            assertThat(updated.getTitleStatus()).isEqualTo(TitleStatus.USER_EDITED);
        }
    }

    // ── 3. Explorer에서 titleStatus 반환 확인 ──

    @Nested
    @DisplayName("Explorer titleStatus 반환")
    class ExplorerTitleStatus {

        @Test
        @DisplayName("PENDING 상태 chat — titleStatus=PENDING 반환")
        void pendingStatus() throws Exception {
            Member member = createMember("explPending@test.com");
            createChatPending(member);
            em.flush();
            em.clear();

            mockMvc.perform(get("/chats/explorer")
                            .header("Authorization", "Bearer " + tokenFor(member)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.roots[0].nodes[0].titleStatus").value("PENDING"))
                    .andExpect(jsonPath("$.result.roots[0].nodes[0].title").isEmpty());
        }

        @Test
        @DisplayName("GENERATED 상태 chat — titleStatus=GENERATED, title 포함")
        void generatedStatus() throws Exception {
            Member member = createMember("explGen@test.com");
            Chat chat = createChatPending(member);
            chat.updateGeneratedTitle("AI 제목");
            chatRepository.flush();
            em.flush();
            em.clear();

            mockMvc.perform(get("/chats/explorer")
                            .header("Authorization", "Bearer " + tokenFor(member)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.roots[0].nodes[0].titleStatus").value("GENERATED"))
                    .andExpect(jsonPath("$.result.roots[0].nodes[0].title").value("AI 제목"));
        }

        @Test
        @DisplayName("분기의 titleStatus도 올바르게 반환")
        void branchTitleStatus() throws Exception {
            Member member = createMember("explBrTitle@test.com");
            Chat root = createChat(member);
            Turn turn = createCompletedTurn(root, 1, "질문", "답변");
            Chat branch = createBranch(root, turn);
            branch.updateGeneratedTitle("분기 AI 제목");
            chatRepository.flush();
            em.flush();
            em.clear();

            mockMvc.perform(get("/chats/explorer")
                            .header("Authorization", "Bearer " + tokenFor(member)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.roots[0].nodes[1].titleStatus").value("GENERATED"))
                    .andExpect(jsonPath("$.result.roots[0].nodes[1].title").value("분기 AI 제목"));
        }
    }

    // ── 4. ContextAssembler 프로퍼티 기반 압축 ──

    @Nested
    @DisplayName("ContextAssembler 프로퍼티 기반 동작")
    class ContextAssemblerTest {

        @Test
        @DisplayName("짧은 대화 — 압축 미적용")
        void shortConversation_noCompression() {
            Member member = createMember("ctx1@test.com");
            Chat chat = createChat(member);
            Turn turn1 = createCompletedTurn(chat, 1, "안녕", "반가워요");
            Turn turn2 = turnRepository.saveAndFlush(Turn.builder()
                    .chat(chat).turnSequence(2).build());
            em.flush();
            em.clear();

            chat = chatRepository.findById(chat.getId()).orElseThrow();
            turn2 = turnRepository.findById(turn2.getId()).orElseThrow();

            ContextAssembler.ContextResult result =
                    contextAssembler.buildContext(chat, turn2, "새 질문");

            assertThat(result.compressionApplied()).isFalse();
            assertThat(result.compressedTurnCount()).isEqualTo(0);
            assertThat(result.messages()).isNotEmpty();
            // 마지막 메시지는 새 user 메시지여야 함
            var lastMsg = result.messages().get(result.messages().size() - 1);
            assertThat(lastMsg.get("role")).isEqualTo("user");
            assertThat(lastMsg.get("content")).isEqualTo("새 질문");
        }

        @Test
        @DisplayName("ancestor chain 맥락 조립 — 분기 경로만 포함")
        void ancestorChain_onlyBranchPath() {
            Member member = createMember("ctx2@test.com");
            Chat root = createChat(member);
            Turn rootTurn1 = createCompletedTurn(root, 1, "루트 질문1", "루트 답변1");
            Turn rootTurn2 = createCompletedTurn(root, 2, "루트 질문2", "루트 답변2");
            createCompletedTurn(root, 3, "루트 질문3", "루트 답변3");

            Chat branch = createBranch(root, rootTurn2);
            Turn branchTurn1 = createCompletedTurn(branch, 1, "분기 질문1", "분기 답변1");
            Turn branchTurn2 = turnRepository.saveAndFlush(Turn.builder()
                    .chat(branch).turnSequence(2).build());
            em.flush();
            em.clear();

            branch = chatRepository.findById(branch.getId()).orElseThrow();
            branchTurn2 = turnRepository.findById(branchTurn2.getId()).orElseThrow();

            ContextAssembler.ContextResult result =
                    contextAssembler.buildContext(branch, branchTurn2, "분기 새 질문");

            // root의 turn1, turn2까지만 (branchPoint=turn2) + branch의 turn1 + 새 메시지
            // root turn3은 분기 이후이므로 제외되어야 함
            boolean hasRootTurn3 = result.messages().stream()
                    .anyMatch(m -> "루트 질문3".equals(m.get("content"))
                            || "루트 답변3".equals(m.get("content")));
            assertThat(hasRootTurn3).isFalse();

            boolean hasRootTurn1 = result.messages().stream()
                    .anyMatch(m -> "루트 질문1".equals(m.get("content")));
            assertThat(hasRootTurn1).isTrue();

            boolean hasBranchTurn1 = result.messages().stream()
                    .anyMatch(m -> "분기 질문1".equals(m.get("content")));
            assertThat(hasBranchTurn1).isTrue();
        }

        @Test
        @DisplayName("contextTokens가 양수로 반환됨")
        void contextTokens_positive() {
            Member member = createMember("ctx3@test.com");
            Chat chat = createChat(member);
            createCompletedTurn(chat, 1, "질문입니다", "답변입니다");
            Turn turn2 = turnRepository.saveAndFlush(Turn.builder()
                    .chat(chat).turnSequence(2).build());
            em.flush();
            em.clear();

            chat = chatRepository.findById(chat.getId()).orElseThrow();
            turn2 = turnRepository.findById(turn2.getId()).orElseThrow();

            ContextAssembler.ContextResult result =
                    contextAssembler.buildContext(chat, turn2, "새 질문");

            assertThat(result.contextTokens()).isGreaterThan(0);
        }
    }

    // ── 5. Chat Detail에서 llmProvider/llmModel 응답 확인 ──

    @Nested
    @DisplayName("Chat Detail API llmProvider/llmModel")
    class ChatDetailProvider {

        @Test
        @DisplayName("LOCAL provider/model이 응답에 포함됨")
        void localProviderInResponse() throws Exception {
            Member member = createMember("detail1@test.com");
            Chat chat = createChat(member);
            em.flush();
            em.clear();

            mockMvc.perform(get("/chats/" + chat.getId())
                            .header("Authorization", "Bearer " + tokenFor(member)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.llmProvider").value("LOCAL"))
                    .andExpect(jsonPath("$.result.llmModel").value("local-default"));
        }
    }

    // ── 6. Graph API에서 titleStatus 반환 확인 ──

    @Nested
    @DisplayName("Graph API titleStatus")
    class GraphTitleStatus {

        @Test
        @DisplayName("그래프 조회 시 chat의 titleStatus 포함")
        void graphIncludesTitleStatus() throws Exception {
            Member member = createMember("graph1@test.com");
            Chat root = createChat(member);
            Turn turn = createCompletedTurn(root, 1, "질문", "답변");
            Chat branch = createBranch(root, turn);
            branch.updateGeneratedTitle("분기 제목");
            chatRepository.flush();
            em.flush();
            em.clear();

            mockMvc.perform(get("/chats/" + root.getId() + "/graph")
                            .header("Authorization", "Bearer " + tokenFor(member)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.chats[?(@.chatId==%d)].titleStatus",
                            branch.getId()).value("GENERATED"));
        }

        @Test
        @DisplayName("PENDING 분기가 그래프에 포함")
        void graphPendingBranch() throws Exception {
            Member member = createMember("graph2@test.com");
            Chat root = createChat(member);
            Turn turn = createCompletedTurn(root, 1, "질문", "답변");
            createBranch(root, turn);
            em.flush();
            em.clear();

            mockMvc.perform(get("/chats/" + root.getId() + "/graph")
                            .header("Authorization", "Bearer " + tokenFor(member)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.chats.length()").value(2));
        }
    }
}
