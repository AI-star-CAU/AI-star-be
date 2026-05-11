package com.aistar.backend;

import com.aistar.backend.domain.chat.entity.Chat;
import com.aistar.backend.domain.chat.entity.Message;
import com.aistar.backend.domain.chat.enums.LlmModel;
import com.aistar.backend.domain.chat.enums.LlmProvider;
import com.aistar.backend.domain.chat.enums.MessageStatus;
import com.aistar.backend.domain.chat.enums.SenderType;
import com.aistar.backend.domain.chat.repository.ChatRepository;
import com.aistar.backend.domain.chat.repository.MessageRepository;
import com.aistar.backend.domain.chat.service.MessageService;
import com.aistar.backend.domain.member.entity.Member;
import com.aistar.backend.domain.member.repository.MemberRepository;
import com.aistar.backend.global.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
class Phase2ApiTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired MemberRepository memberRepository;
    @Autowired ChatRepository chatRepository;
    @Autowired MessageRepository messageRepository;
    @Autowired MessageService messageService;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired EntityManager em;

    // ── Helper ──

    private Member createMember(String email, String name) {
        Member member = Member.builder()
                .email(email)
                .password(passwordEncoder.encode("password1234"))
                .name(name)
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
                .llmProvider(LlmProvider.OPENAI)
                .llmModel(LlmModel.GPT_4O_MINI)
                .member(member)
                .build();
        chatRepository.saveAndFlush(chat);
        chat.initRootChatId();
        chatRepository.flush();
        return chat;
    }

    // ── 1. Auth ──

    @Test
    @DisplayName("회원가입 성공")
    void signUp_success() throws Exception {
        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"new@test.com","password":"password1234","name":"테스터"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.code").value("COMMON_201"))
                .andExpect(jsonPath("$.result.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.result.email").value("new@test.com"));
    }

    @Test
    @DisplayName("이메일 중복 시 409")
    void signUp_duplicateEmail_409() throws Exception {
        createMember("dup@test.com", "기존회원");

        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"dup@test.com","password":"password1234","name":"새회원"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("MEMBER_4091"));
    }

    @Test
    @DisplayName("로그인 성공")
    void login_success() throws Exception {
        createMember("login@test.com", "로그인유저");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"login@test.com","password":"password1234"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.result.accessToken").isNotEmpty());
    }

    @Test
    @DisplayName("로그인 실패 시 401")
    void login_failure_401() throws Exception {
        createMember("fail@test.com", "유저");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"fail@test.com","password":"wrongpassword"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("AUTH_4012"));
    }

    // ── 2. Security ──

    @Test
    @DisplayName("토큰 없는 보호 API 접근 시 401")
    void protectedApi_noToken_401() throws Exception {
        mockMvc.perform(get("/chats"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.isSuccess").value(false));
    }

    @Test
    @DisplayName("다른 사용자의 chat 접근 시 403")
    void otherUserChat_403() throws Exception {
        Member owner = createMember("owner@test.com", "소유자");
        Member other = createMember("other@test.com", "다른유저");
        Chat chat = createChat(owner);

        mockMvc.perform(get("/chats/" + chat.getId())
                        .header("Authorization", "Bearer " + tokenFor(other)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_403"));
    }

    // ── 3. Chat ──

    @Test
    @DisplayName("새 대화 생성 성공")
    void createChat_success() throws Exception {
        Member member = createMember("chat@test.com", "챗유저");

        mockMvc.perform(post("/chats")
                        .header("Authorization", "Bearer " + tokenFor(member))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"새 대화","llmProvider":"OPENAI","llmModel":"GPT_4O_MINI"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.result.title").value("새 대화"))
                .andExpect(jsonPath("$.result.llmModel").value("GPT_4O_MINI"));
    }

    @Test
    @DisplayName("대화 목록 조회 성공")
    void getChatList_success() throws Exception {
        Member member = createMember("list@test.com", "목록유저");
        createChat(member);
        createChat(member);

        mockMvc.perform(get("/chats")
                        .header("Authorization", "Bearer " + tokenFor(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.result.content.length()").value(2));
    }

    @Test
    @DisplayName("대화 메타정보 조회 성공")
    void getChatDetail_success() throws Exception {
        Member member = createMember("detail@test.com", "조회유저");
        Chat chat = createChat(member);

        mockMvc.perform(get("/chats/" + chat.getId())
                        .header("Authorization", "Bearer " + tokenFor(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.result.chatId").value(chat.getId()))
                .andExpect(jsonPath("$.result.title").value("테스트 대화"));
    }

    // ── 4. Turn ──

    @Test
    @DisplayName("턴 목록 조회 성공")
    void getTurns_success() throws Exception {
        Member member = createMember("turn@test.com", "턴유저");
        Chat chat = createChat(member);

        // 턴 2개 생성
        messageService.createTurnAndMessages(chat.getId(), "첫 번째 질문");
        messageService.createTurnAndMessages(chat.getId(), "두 번째 질문");
        em.flush();

        mockMvc.perform(get("/chats/" + chat.getId() + "/turns")
                        .header("Authorization", "Bearer " + tokenFor(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.result.turns.length()").value(2));
    }

    // ── 5. Message ──

    @Test
    @DisplayName("메시지 송신 시 turn/userMessage/aiMessage 생성")
    void sendMessage_createsTurnAndMessages() {
        Member member = createMember("msg@test.com", "메시지유저");
        Chat chat = createChat(member);

        MessageService.TurnContext ctx = messageService.createTurnAndMessages(chat.getId(), "안녕하세요");
        em.flush();
        em.clear();

        // Turn 생성 확인
        assertThat(ctx.turn().getId()).isNotNull();
        assertThat(ctx.turn().getTurnSequence()).isEqualTo(1);

        // User message 확인
        Message userMsg = messageRepository.findById(ctx.userMessage().getId()).orElseThrow();
        assertThat(userMsg.getSenderType()).isEqualTo(SenderType.USER);
        assertThat(userMsg.getContent()).isEqualTo("안녕하세요");
        assertThat(userMsg.getStatus()).isEqualTo(MessageStatus.COMPLETED);

        // AI message 확인
        Message aiMsg = messageRepository.findById(ctx.aiMessage().getId()).orElseThrow();
        assertThat(aiMsg.getSenderType()).isEqualTo(SenderType.AI);
        assertThat(aiMsg.getStatus()).isEqualTo(MessageStatus.STREAMING);
    }

    @Test
    @DisplayName("AI 응답 완료 시 message status COMPLETED")
    void aiResponse_completed() {
        Member member = createMember("complete@test.com", "완료유저");
        Chat chat = createChat(member);

        MessageService.TurnContext ctx = messageService.createTurnAndMessages(chat.getId(), "질문");
        Long aiMessageId = ctx.aiMessage().getId();

        messageService.completeAiMessage(aiMessageId, "AI 응답 내용입니다.", 5);
        em.flush();
        em.clear();

        Message aiMsg = messageRepository.findById(aiMessageId).orElseThrow();
        assertThat(aiMsg.getStatus()).isEqualTo(MessageStatus.COMPLETED);
        assertThat(aiMsg.getContent()).isEqualTo("AI 응답 내용입니다.");
        assertThat(aiMsg.getAnswerToken()).isEqualTo(5);
    }

    @Test
    @DisplayName("cancel 호출 시 STREAMING -> CANCELED")
    void cancel_streaming_toCanceled() throws Exception {
        Member member = createMember("cancel@test.com", "취소유저");
        Chat chat = createChat(member);

        MessageService.TurnContext ctx = messageService.createTurnAndMessages(chat.getId(), "질문");
        Long aiMessageId = ctx.aiMessage().getId();
        em.flush();

        mockMvc.perform(post("/chats/" + chat.getId() + "/messages/" + aiMessageId + "/cancel")
                        .header("Authorization", "Bearer " + tokenFor(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.result.messageId").value(aiMessageId))
                .andExpect(jsonPath("$.result.status").value("CANCELED"));
    }

    @Test
    @DisplayName("COMPLETED 메시지 cancel 시 409")
    void cancel_completed_409() throws Exception {
        Member member = createMember("cancel409@test.com", "409유저");
        Chat chat = createChat(member);

        MessageService.TurnContext ctx = messageService.createTurnAndMessages(chat.getId(), "질문");
        Long aiMessageId = ctx.aiMessage().getId();

        messageService.completeAiMessage(aiMessageId, "완료된 응답", 3);
        em.flush();

        mockMvc.perform(post("/chats/" + chat.getId() + "/messages/" + aiMessageId + "/cancel")
                        .header("Authorization", "Bearer " + tokenFor(member)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("MESSAGE_4091"));
    }
}
