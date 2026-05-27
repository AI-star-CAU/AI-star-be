package com.aistar.backend;

import com.aistar.backend.domain.chat.entity.Chat;
import com.aistar.backend.domain.chat.entity.Message;
import com.aistar.backend.domain.chat.entity.Turn;
import com.aistar.backend.domain.chat.enums.LlmModel;
import com.aistar.backend.domain.chat.enums.LlmProvider;
import com.aistar.backend.domain.chat.enums.MessageStatus;
import com.aistar.backend.domain.chat.enums.SenderType;
import com.aistar.backend.domain.chat.enums.TitleStatus;
import com.aistar.backend.domain.chat.repository.ChatRepository;
import com.aistar.backend.domain.chat.repository.MessageRepository;
import com.aistar.backend.domain.chat.repository.TurnRepository;
import com.aistar.backend.domain.chat.service.MessageService;
import com.aistar.backend.domain.member.entity.Member;
import com.aistar.backend.domain.member.repository.MemberRepository;
import com.aistar.backend.global.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
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
class Phase3ApiTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired MemberRepository memberRepository;
    @Autowired ChatRepository chatRepository;
    @Autowired TurnRepository turnRepository;
    @Autowired MessageRepository messageRepository;
    @Autowired MessageService messageService;
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

    // ── 1. Branch 생성 ──

    @Nested
    @DisplayName("POST /chats/{chatId}/branches")
    class CreateBranch {

        @Test
        @DisplayName("분기 생성 성공 — parentId, rootChatId, branchPointTurnId 확인")
        void success() throws Exception {
            Member member = createMember("branch@test.com");
            Chat chat = createChat(member);
            Turn turn1 = createCompletedTurn(chat, 1, "질문1", "답변1");
            createCompletedTurn(chat, 2, "질문2", "답변2");

            mockMvc.perform(post("/chats/" + chat.getId() + "/branches")
                            .header("Authorization", "Bearer " + tokenFor(member))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"branchPointTurnId":%d,"title":"분기 테스트"}
                                    """.formatted(turn1.getId())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.isSuccess").value(true))
                    .andExpect(jsonPath("$.result.parentId").value(chat.getId()))
                    .andExpect(jsonPath("$.result.rootChatId").value(chat.getRootChatId()))
                    .andExpect(jsonPath("$.result.branchPointTurnId").value(turn1.getId()))
                    .andExpect(jsonPath("$.result.title").value("분기 테스트"))
                    .andExpect(jsonPath("$.result.titleStatus").value("USER_EDITED"))
                    .andExpect(jsonPath("$.result.llmModel").value("local-default"));
        }

        @Test
        @DisplayName("제목 없이 분기 생성 — titleStatus=PENDING")
        void success_noTitle() throws Exception {
            Member member = createMember("branch2@test.com");
            Chat chat = createChat(member);
            Turn turn = createCompletedTurn(chat, 1, "질문", "답변");

            mockMvc.perform(post("/chats/" + chat.getId() + "/branches")
                            .header("Authorization", "Bearer " + tokenFor(member))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"branchPointTurnId":%d}
                                    """.formatted(turn.getId())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.result.title").doesNotExist())
                    .andExpect(jsonPath("$.result.titleStatus").value("PENDING"));
        }

        @Test
        @DisplayName("잘못된 turnId로 분기 생성 시 400")
        void invalidTurn_400() throws Exception {
            Member member = createMember("branch3@test.com");
            Chat chat = createChat(member);

            mockMvc.perform(post("/chats/" + chat.getId() + "/branches")
                            .header("Authorization", "Bearer " + tokenFor(member))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"branchPointTurnId":99999}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BRANCH_4001"));
        }

        @Test
        @DisplayName("다른 사용자의 chat에 분기 생성 시 403")
        void otherUser_403() throws Exception {
            Member owner = createMember("branchOwner@test.com");
            Member other = createMember("branchOther@test.com");
            Chat chat = createChat(owner);
            Turn turn = createCompletedTurn(chat, 1, "질문", "답변");

            mockMvc.perform(post("/chats/" + chat.getId() + "/branches")
                            .header("Authorization", "Bearer " + tokenFor(other))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"branchPointTurnId":%d}
                                    """.formatted(turn.getId())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("COMMON_403"));
        }
    }

    // ── 2. 제목 수정 ──

    @Nested
    @DisplayName("PATCH /chats/{chatId}")
    class UpdateTitle {

        @Test
        @DisplayName("제목 수정 성공 — titleStatus=USER_EDITED")
        void success() throws Exception {
            Member member = createMember("title@test.com");
            Chat chat = createChat(member);

            mockMvc.perform(patch("/chats/" + chat.getId())
                            .header("Authorization", "Bearer " + tokenFor(member))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"title":"수정된 제목"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.title").value("수정된 제목"))
                    .andExpect(jsonPath("$.result.titleStatus").value("USER_EDITED"));
        }

        @Test
        @DisplayName("빈 제목으로 수정 시 400")
        void blankTitle_400() throws Exception {
            Member member = createMember("titleblank@test.com");
            Chat chat = createChat(member);

            mockMvc.perform(patch("/chats/" + chat.getId())
                            .header("Authorization", "Bearer " + tokenFor(member))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"title":"   "}
                                    """))
                    .andExpect(status().isBadRequest());
        }
    }

    // ── 3. 삭제 (cascade soft delete) ──

    @Nested
    @DisplayName("DELETE /chats/{chatId}")
    class DeleteChat {

        @Test
        @DisplayName("삭제 성공 — soft delete")
        void success() throws Exception {
            Member member = createMember("delete@test.com");
            Chat chat = createChat(member);

            mockMvc.perform(delete("/chats/" + chat.getId())
                            .header("Authorization", "Bearer " + tokenFor(member)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.isSuccess").value(true));

            em.flush();
            em.clear();

            Chat deleted = chatRepository.findById(chat.getId()).orElseThrow();
            assertThat(deleted.getDeletedAt()).isNotNull();
        }

        @Test
        @DisplayName("cascade 삭제 — 자손 branch도 함께 soft delete")
        void cascadeDelete() throws Exception {
            Member member = createMember("cascade@test.com");
            Chat root = createChat(member);
            Turn turn = createCompletedTurn(root, 1, "질문", "답변");
            Chat child = createBranch(root, turn);
            Turn childTurn = createCompletedTurn(child, 1, "분기질문", "분기답변");
            Chat grandchild = createBranch(child, childTurn);

            mockMvc.perform(delete("/chats/" + root.getId())
                            .header("Authorization", "Bearer " + tokenFor(member)))
                    .andExpect(status().isOk());

            em.flush();
            em.clear();

            assertThat(chatRepository.findById(root.getId()).get().getDeletedAt()).isNotNull();
            assertThat(chatRepository.findById(child.getId()).get().getDeletedAt()).isNotNull();
            assertThat(chatRepository.findById(grandchild.getId()).get().getDeletedAt()).isNotNull();
        }

        @Test
        @DisplayName("이미 삭제된 chat 재삭제 시 409")
        void alreadyDeleted_409() throws Exception {
            Member member = createMember("deldup@test.com");
            Chat chat = createChat(member);
            chat.softDelete();
            chatRepository.flush();

            mockMvc.perform(delete("/chats/" + chat.getId())
                            .header("Authorization", "Bearer " + tokenFor(member)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("BRANCH_4091"));
        }

        @Test
        @DisplayName("다른 사용자의 chat 삭제 시 403")
        void otherUser_403() throws Exception {
            Member owner = createMember("delOwner@test.com");
            Member other = createMember("delOther@test.com");
            Chat chat = createChat(owner);

            mockMvc.perform(delete("/chats/" + chat.getId())
                            .header("Authorization", "Bearer " + tokenFor(other)))
                    .andExpect(status().isForbidden());
        }
    }

    // ── 4. 응답 재생성 ──

    @Nested
    @DisplayName("POST /chats/{chatId}/messages/{messageId}/regenerate")
    class Regenerate {

        @Test
        @DisplayName("재생성 시 기존 AI 메시지가 STREAMING으로 초기화")
        void resetsAiMessage() {
            Member member = createMember("regen@test.com");
            Chat chat = createChat(member);
            Turn turn = createCompletedTurn(chat, 1, "질문", "기존 응답");
            em.flush();
            em.clear();

            Message aiMsg = messageRepository.findAll().stream()
                    .filter(m -> m.getTurn().getId().equals(turn.getId())
                            && m.getSenderType() == SenderType.ASSISTANT)
                    .findFirst().orElseThrow();

            MessageService.TurnContext ctx = messageService.regenerateMessage(
                    member.getId(), chat.getId(), aiMsg.getId());

            assertThat(ctx.aiMessage().getStatus()).isEqualTo(MessageStatus.STREAMING);
            assertThat(ctx.aiMessage().getContent()).isNull();
            assertThat(ctx.turn().getId()).isEqualTo(turn.getId());
        }

        @Test
        @DisplayName("USER 메시지 재생성 시 409")
        void userMessage_409() throws Exception {
            Member member = createMember("regenUser@test.com");
            Chat chat = createChat(member);
            Turn turn = createCompletedTurn(chat, 1, "질문", "답변");
            em.flush();
            em.clear();

            Message userMsg = messageRepository.findAll().stream()
                    .filter(m -> m.getTurn().getId().equals(turn.getId())
                            && m.getSenderType() == SenderType.USER)
                    .findFirst().orElseThrow();

            mockMvc.perform(post("/chats/" + chat.getId() + "/messages/" + userMsg.getId() + "/regenerate")
                            .header("Authorization", "Bearer " + tokenFor(member)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("MESSAGE_4092"));
        }

        @Test
        @DisplayName("다른 chat의 message 재생성 시 404")
        void otherChatMessage_404() throws Exception {
            Member member = createMember("regenCross@test.com");
            Chat chatA = createChat(member);
            Chat chatB = createChat(member);
            Turn turn = createCompletedTurn(chatA, 1, "질문", "답변");
            em.flush();
            em.clear();

            Message aiMsg = messageRepository.findAll().stream()
                    .filter(m -> m.getTurn().getId().equals(turn.getId())
                            && m.getSenderType() == SenderType.ASSISTANT)
                    .findFirst().orElseThrow();

            mockMvc.perform(post("/chats/" + chatB.getId() + "/messages/" + aiMsg.getId() + "/regenerate")
                            .header("Authorization", "Bearer " + tokenFor(member)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("MESSAGE_4041"));
        }
    }

    // ── 5. 메시지 수정 (자동 분기) ──

    @Nested
    @DisplayName("PATCH /chats/{chatId}/messages/{messageId}")
    class EditMessage {

        @Test
        @DisplayName("수정 시 새 branch 생성 — turn seq > 1이면 직전 turn이 branchPoint")
        void createsBranch() {
            Member member = createMember("edit@test.com");
            Chat chat = createChat(member);
            Turn turn1 = createCompletedTurn(chat, 1, "질문1", "답변1");
            Turn turn2 = createCompletedTurn(chat, 2, "질문2", "답변2");
            em.flush();
            em.clear();

            Message userMsg = messageRepository.findAll().stream()
                    .filter(m -> m.getTurn().getId().equals(turn2.getId())
                            && m.getSenderType() == SenderType.USER)
                    .findFirst().orElseThrow();

            MessageService.TurnContext ctx = messageService.editMessage(
                    member.getId(), chat.getId(), userMsg.getId(), "수정된 질문");

            assertThat(ctx.branchCreated()).isNotNull();
            assertThat(ctx.branchCreated().branchPointTurnId()).isEqualTo(turn1.getId());
            assertThat(ctx.userMessage().getContent()).isEqualTo("수정된 질문");
        }

        @Test
        @DisplayName("ASSISTANT 메시지 수정 시 409")
        void assistantMessage_409() throws Exception {
            Member member = createMember("editAi@test.com");
            Chat chat = createChat(member);
            Turn turn = createCompletedTurn(chat, 1, "질문", "답변");
            em.flush();
            em.clear();

            Message aiMsg = messageRepository.findAll().stream()
                    .filter(m -> m.getTurn().getId().equals(turn.getId())
                            && m.getSenderType() == SenderType.ASSISTANT)
                    .findFirst().orElseThrow();

            mockMvc.perform(patch("/chats/" + chat.getId() + "/messages/" + aiMsg.getId())
                            .header("Authorization", "Bearer " + tokenFor(member))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"content":"수정 시도"}
                                    """))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("MESSAGE_4092"));
        }

        @Test
        @DisplayName("root의 첫 turn user 메시지 수정 시 409 — 분기점 없음")
        void rootFirstTurn_409() throws Exception {
            Member member = createMember("editFirst@test.com");
            Chat chat = createChat(member);
            Turn turn = createCompletedTurn(chat, 1, "질문", "답변");
            em.flush();
            em.clear();

            Message userMsg = messageRepository.findAll().stream()
                    .filter(m -> m.getTurn().getId().equals(turn.getId())
                            && m.getSenderType() == SenderType.USER)
                    .findFirst().orElseThrow();

            mockMvc.perform(patch("/chats/" + chat.getId() + "/messages/" + userMsg.getId())
                            .header("Authorization", "Bearer " + tokenFor(member))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"content":"수정 시도"}
                                    """))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("MESSAGE_4092"));
        }
    }

    // ── 6. 그래프 조회 ──

    @Nested
    @DisplayName("GET /chats/{chatId}/graph")
    class GetGraph {

        @Test
        @DisplayName("기본 그래프 조회 성공 — rootChatId, center, turns, chats 포함")
        void success() throws Exception {
            Member member = createMember("graph@test.com");
            Chat chat = createChat(member);
            Turn turn1 = createCompletedTurn(chat, 1, "질문1", "답변1");
            Turn turn2 = createCompletedTurn(chat, 2, "질문2", "답변2");
            em.flush();
            em.clear();

            mockMvc.perform(get("/chats/" + chat.getId() + "/graph")
                            .header("Authorization", "Bearer " + tokenFor(member)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.isSuccess").value(true))
                    .andExpect(jsonPath("$.result.rootChatId").value(chat.getRootChatId()))
                    .andExpect(jsonPath("$.result.center.chatId").value(chat.getId()))
                    .andExpect(jsonPath("$.result.chats").isArray())
                    .andExpect(jsonPath("$.result.turns").isArray())
                    .andExpect(jsonPath("$.result.turns.length()").value(2))
                    .andExpect(jsonPath("$.result.frontier").exists());
        }

        @Test
        @DisplayName("분기가 있는 그래프 — chats에 branch 포함, turns는 윈도우 내만")
        void withBranch() throws Exception {
            Member member = createMember("graphBranch@test.com");
            Chat root = createChat(member);
            Turn turn1 = createCompletedTurn(root, 1, "질문1", "답변1");
            Turn turn2 = createCompletedTurn(root, 2, "질문2", "답변2");

            Chat branch = createBranch(root, turn1);
            Turn bTurn1 = createCompletedTurn(branch, 1, "분기질문", "분기답변");
            em.flush();
            em.clear();

            // center=turn2(root의 lastTurn). chats는 트리 전체(2개), turns는 윈도우 내(2개)
            mockMvc.perform(get("/chats/" + root.getId() + "/graph")
                            .header("Authorization", "Bearer " + tokenFor(member)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.chats.length()").value(2))
                    .andExpect(jsonPath("$.result.turns.length()").value(2));

            // branch chat으로 그래프 조회하면 branch의 turn이 윈도우에 포함
            mockMvc.perform(get("/chats/" + branch.getId() + "/graph")
                            .header("Authorization", "Bearer " + tokenFor(member)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.chats.length()").value(2))
                    .andExpect(jsonPath("$.result.center.chatId").value(branch.getId()));
        }

        @Test
        @DisplayName("centerTurnId 지정 조회")
        void withCenter() throws Exception {
            Member member = createMember("graphCenter@test.com");
            Chat chat = createChat(member);
            Turn turn1 = createCompletedTurn(chat, 1, "질문1", "답변1");
            Turn turn2 = createCompletedTurn(chat, 2, "질문2", "답변2");
            em.flush();
            em.clear();

            mockMvc.perform(get("/chats/" + chat.getId() + "/graph")
                            .param("centerTurnId", turn1.getId().toString())
                            .header("Authorization", "Bearer " + tokenFor(member)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.center.turnId").value(turn1.getId()));
        }

        @Test
        @DisplayName("up/down 범위 초과 시 400")
        void invalidParam_400() throws Exception {
            Member member = createMember("graphInvalid@test.com");
            Chat chat = createChat(member);
            createCompletedTurn(chat, 1, "질문", "답변");

            mockMvc.perform(get("/chats/" + chat.getId() + "/graph")
                            .param("up", "0")
                            .header("Authorization", "Bearer " + tokenFor(member)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("GRAPH_4001"));
        }

        @Test
        @DisplayName("다른 사용자의 chat 그래프 조회 시 403")
        void otherUser_403() throws Exception {
            Member owner = createMember("graphOwner@test.com");
            Member other = createMember("graphOther@test.com");
            Chat chat = createChat(owner);
            createCompletedTurn(chat, 1, "질문", "답변");

            mockMvc.perform(get("/chats/" + chat.getId() + "/graph")
                            .header("Authorization", "Bearer " + tokenFor(other)))
                    .andExpect(status().isForbidden());
        }
    }

    // ── 7. 그래프 윈도우 확장 ──

    @Nested
    @DisplayName("GET /chats/{chatId}/graph/expand")
    class ExpandWindow {

        @Test
        @DisplayName("DOWN 방향 확장 성공")
        void expandDown() throws Exception {
            Member member = createMember("expand@test.com");
            Chat chat = createChat(member);
            Turn turn1 = createCompletedTurn(chat, 1, "질문1", "답변1");
            Turn turn2 = createCompletedTurn(chat, 2, "질문2", "답변2");
            Turn turn3 = createCompletedTurn(chat, 3, "질문3", "답변3");
            em.flush();
            em.clear();

            mockMvc.perform(get("/chats/" + chat.getId() + "/graph/expand")
                            .param("fromTurnId", turn1.getId().toString())
                            .param("direction", "DOWN")
                            .param("limit", "10")
                            .header("Authorization", "Bearer " + tokenFor(member)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.direction").value("DOWN"))
                    .andExpect(jsonPath("$.result.turns").isArray())
                    .andExpect(jsonPath("$.result.frontier").exists());
        }

        @Test
        @DisplayName("UP 방향 확장 성공")
        void expandUp() throws Exception {
            Member member = createMember("expandUp@test.com");
            Chat chat = createChat(member);
            Turn turn1 = createCompletedTurn(chat, 1, "질문1", "답변1");
            Turn turn2 = createCompletedTurn(chat, 2, "질문2", "답변2");
            Turn turn3 = createCompletedTurn(chat, 3, "질문3", "답변3");
            em.flush();
            em.clear();

            mockMvc.perform(get("/chats/" + chat.getId() + "/graph/expand")
                            .param("fromTurnId", turn3.getId().toString())
                            .param("direction", "UP")
                            .param("limit", "10")
                            .header("Authorization", "Bearer " + tokenFor(member)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.direction").value("UP"))
                    .andExpect(jsonPath("$.result.turns").isArray());
        }

        @Test
        @DisplayName("잘못된 direction 시 400")
        void invalidDirection_400() throws Exception {
            Member member = createMember("expandBad@test.com");
            Chat chat = createChat(member);
            Turn turn = createCompletedTurn(chat, 1, "질문", "답변");

            mockMvc.perform(get("/chats/" + chat.getId() + "/graph/expand")
                            .param("fromTurnId", turn.getId().toString())
                            .param("direction", "LEFT")
                            .header("Authorization", "Bearer " + tokenFor(member)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("GRAPH_4001"));
        }
    }

}
