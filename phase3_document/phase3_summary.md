# Phase 3 구현 요약

## 1. 개요

Phase 2(Walking Skeleton)에서 구현한 기본 대화 기능 위에, **분기(Branch) 트리 구조**와 **그래프 시각화 API**를 추가하여 대화 이력을 트리 형태로 관리할 수 있도록 확장하였다.

**명세서**: `api_phase3_v4.md` (v0.5)  
**ERD**: `AIT ERD phase3.txt / .png`

---

## 2. 구현 API 목록

### 2.1 Phase 2에서 이어지는 API (기존)

| Method | Endpoint | 설명 |
|--------|----------|------|
| POST | `/auth/signup` | 회원가입 |
| POST | `/auth/login` | 로그인 (JWT 발급) |
| GET | `/members/me` | 내 정보 조회 |
| POST | `/chats` | 새 대화 시작 |
| GET | `/chats` | 대화 목록 조회 (Offset 페이징) |
| GET | `/chats/{chatId}` | 대화 메타정보 조회 |
| GET | `/chats/{chatId}/turns` | 턴 목록 조회 (Cursor 페이징) |
| POST | `/chats/{chatId}/messages` | 메시지 송신 (SSE 스트리밍) |
| POST | `/chats/{chatId}/messages/{messageId}/cancel` | 스트리밍 취소 |

### 2.2 Phase 3 신규 API

| Method | Endpoint | 설명 | 명세서 |
|--------|----------|------|--------|
| POST | `/chats/{chatId}/branches` | 분기 생성 | §2.8 |
| PATCH | `/chats/{chatId}` | 대화/분기 제목 수정 | §2.9 |
| DELETE | `/chats/{chatId}` | 대화 삭제 (cascade soft delete) | §2.10 |
| POST | `/chats/{chatId}/messages/{messageId}/regenerate` | 응답 재생성 (자동 분기) | §4.1 |
| PATCH | `/chats/{chatId}/messages/{messageId}` | 메시지 수정 (자동 분기) | §4.2 |
| GET | `/chats/{chatId}/graph` | 그래프 조회 | §3.1 |
| GET | `/chats/{chatId}/graph/expand` | 그래프 윈도우 확장 | §3.2 |

---

## 3. 핵심 구현 상세

### 3.1 분기(Branch) 트리 구조

`chat` 테이블의 `parent_id`, `root_chat_id`, `branch_point_turn_id` 3개 컬럼으로 트리 구조를 표현한다.

```
root(chat_id=1)
├── turn1 ── turn2 ── turn3
│                      └── branch(chat_id=2, parent_id=1, branch_point_turn_id=turn2)
│                           └── turn1 ── turn2
└── ...
```

- **root_chat_id**: 어떤 chat이든 자신이 속한 트리의 루트 ID를 저장. 루트 자신은 `root_chat_id = chat_id`.
- **parent_id**: 직접 부모 chat의 ID. 루트는 `null`.
- **branch_point_turn_id**: 부모 chat에서 분기가 시작된 turn의 ID.

**ChatService.createBranch()** — 부모 chat의 특정 turn에서 새 분기 chat을 생성한다. LLM 설정(provider, model)은 부모에서 상속한다.

### 3.2 응답 재생성 / 메시지 수정 (자동 분기)

둘 다 기존 대화의 특정 지점에서 새로운 분기를 만들어 SSE 스트리밍을 시작하는 패턴이다.

**공통 로직**: `MessageService.createBranchTurn()` 으로 추출하여 중복을 제거했다.

```
regenerateMessage(messageId)     editMessage(messageId, newContent)
         │                                │
         ▼                                ▼
   대상 메시지 검증               대상 메시지 검증
   (ASSISTANT만 허용)            (USER만 허용)
         │                                │
         └──────── resolveBranchPoint() ───┘
                          │
                          ▼
                 createBranchTurn()
                 ├── 새 branch chat 생성
                 ├── 새 turn + user/ai message 생성
                 └── TurnContext 반환 → streamMessage()
```

**분기점 결정 규칙 (§4.1.1 resolveBranchPoint)**:
1. 대상 turn의 sequence > 1 → 같은 chat의 직전 turn
2. 대상 turn이 첫 turn이고 현재 chat이 branch → 부모의 branchPointTurn
3. root의 첫 turn → 거부 (더 이상 올라갈 곳 없음)

### 3.3 그래프 시각화 API

`GraphService`가 대화 트리를 윈도우 기반으로 탐색하여 시각화 데이터를 제공한다.

**getGraph()** — center turn을 기준으로 UP/DOWN 방향으로 윈도우를 열어 turn 노드를 수집한다.

- **UP 탐색 (ancestor, 단일 경로)**: center turn에서 turn_sequence를 역순으로 따라가며, chat 경계에서는 `branch_point_turn_id`를 통해 부모 chat으로 점프한다.
- **DOWN 탐색 (BFS, 다중 경로)**: center turn에서 순방향으로 진행하며, 해당 turn에서 파생된 branch들도 큐에 넣어 너비 우선 탐색한다.
- **Frontier**: 윈도우 경계에서 추가 데이터 존재 여부(`hasMore`)를 반환한다.

**expandWindow()** — 프론트에서 frontier 지점을 클릭하면 호출되어 한 방향으로만 추가 turn을 가져온다.

### 3.4 SSE 스트리밍 흐름

`MessageService.streamMessage()`이 `SseEmitter`를 반환하고, 내부에서 Virtual Thread를 사용하여 비동기로 LLM 호출 및 이벤트 전송을 수행한다.

```
Controller → createTurnAndMessages() → streamMessage()
                                            │
                                     Virtual Thread 시작
                                            │
                  ┌─────────────────────────┤
                  │ (분기 시) branch_created │
                  ├─────────────────────────┤
                  │ turn_started            │
                  ├─────────────────────────┤
                  │ chunk × N              │  ← LlmClient.streamCompletion()
                  ├─────────────────────────┤
                  │ turn_completed          │  ← DB 저장 (TransactionTemplate)
                  ├─────────────────────────┤
                  │ done                    │
                  └─────────────────────────┘
                            │
                   generateSummaryAsync()  ← 별도 Virtual Thread
```

**취소 흐름**: `cancelMessage()`가 `StreamingContext.canceled` 플래그를 set → 스트리밍 루프에서 `CancelException` throw → 부분 content 저장 후 `cancelled` 이벤트 전송.

### 3.5 title nullable 정책

Phase 3에서 `chat.title`을 **nullable**로 변경했다.

- **이전**: `NOT NULL DEFAULT '제목없음'` — UI 텍스트가 DB에 저장됨
- **이후**: `NULL` — title이 없으면 DB에 `null` 저장, UI 표시는 프론트에서 처리

관련 변경:
- `Chat.java`: `@Column(nullable=false)` + `@Builder.Default "제목없음"` 제거 → nullable
- `ChatService.createChat()`: `"제목없음"` 기본값 제거 → `dto.title()` 그대로 전달
- `ChatService.createBranch()`: `"새 분기"` 기본값 제거 → title 미지정 시 `null`
- `MessageService.createBranchTurn()`: `.title(null)` 사용
- `DataInitializer.java`: 테스트 데이터도 `.title(null)` 적용
- ERD: `chat.title TEXT NULL`, `turn.summary TEXT NULL`

---

## 4. 코드 구현 방식

### 4.1 레이어드 아키텍처 (Controller → Service → Repository)

모든 API가 동일한 3계층 흐름을 따른다.

```
Client 요청
  → Controller: 파라미터 바인딩, 입력 검증(@Valid), 인증 객체 추출
    → Service: 비즈니스 로직, 소유권 검증, 트랜잭션 관리
      → Repository: JPA 쿼리 실행
    → Converter: Entity → DTO 변환
  → ApiResponse 래핑 후 응답
```

**Controller**는 비즈니스 로직을 포함하지 않는다. `@AuthenticationPrincipal`로 인증 정보를 받아 Service에 `memberId`만 전달한다.

```java
// MessageController.java — Controller는 조합만 담당
MessageService.TurnContext ctx = messageService.createTurnAndMessages(memberId, chatId, dto.content());
return messageService.streamMessage(ctx);
```

**Service**에서 모든 비즈니스 규칙을 처리한다. 소유권 검증, 상태 전이, 분기점 결정 등 도메인 로직이 여기에 집중된다.

```java
// ChatService.java — 소유권 검증 패턴 (모든 Service에서 동일하게 사용)
private void validateOwner(Chat chat, Long memberId) {
    if (!chat.getMember().getId().equals(memberId)) {
        throw new ProjectException(ErrorStatus.FORBIDDEN);
    }
}
```

### 4.2 공통 응답 구조 (ApiResponse)

모든 API 응답은 `ApiResponse<T>` 래퍼로 통일된다.

```java
// 성공: {"isSuccess": true, "code": "COMMON_200", "message": "...", "result": {...}}
return ApiResponse.onSuccess(SuccessStatus.OK, result);

// 실패: {"isSuccess": false, "code": "CHAT_4041", "message": "존재하지 않는 대화입니다.", "result": null}
return ApiResponse.onFailure(ErrorStatus.CHAT_NOT_FOUND, null);
```

에러는 `ProjectException` → `GlobalExceptionHandler`에서 잡아 `ApiResponse.onFailure()`로 변환한다. 에러 코드는 `ErrorStatus` enum에 도메인별로 그룹화되어 있다 (AUTH_40xx, CHAT_40xx, MESSAGE_40xx 등).

### 4.3 엔티티 설계 패턴

**Builder 패턴**: 모든 엔티티가 Lombok `@Builder`를 사용한다. 생성 시점에 필요한 값을 명시적으로 전달하고, 이후 변경은 의미 있는 이름의 메서드(`updateTitle`, `softDelete`, `touchUpdatedAt`)로만 허용한다.

```java
// Chat.java — 상태 변경 메서드
public void updateTitle(String title) {
    this.title = title;
    this.titleStatus = TitleStatus.USER_EDITED;  // title 변경 시 상태도 함께 전이
}

public void softDelete() {
    this.deletedAt = LocalDateTime.now();  // 물리 삭제가 아닌 soft delete
}
```

**BaseEntity 상속**: `createdAt`, `updatedAt`을 공통으로 관리한다. `@PrePersist`, `@PreUpdate`로 자동 세팅.

**Soft Delete**: `deleted_at` 컬럼이 null이면 활성, 값이 있으면 삭제 상태. 조회 시 항상 `deletedAtIsNull` 조건을 붙인다.

```java
// ChatRepository.java — soft delete 적용된 조회
Optional<Chat> findByIdAndDeletedAtIsNull(Long chatId);
Page<Chat> findByMemberIdAndParentIdIsNullAndDeletedAtIsNull(Long memberId, Pageable pageable);
```

### 4.4 트리 구조의 DB 표현 (Adjacency List)

chat 간의 부모-자식 관계를 `parent_id` FK로 표현하는 **인접 리스트(Adjacency List)** 방식이다.

```java
// Chat.java — 트리 관련 필드
private Long parentId;           // 부모 chat ID (null = root)
private Long rootChatId;         // 트리의 최상위 root ID
private Long branchPointTurnId;  // 부모 chat에서 분기한 turn ID
```

**root_chat_id**를 별도로 저장하는 이유: 같은 트리에 속하는 모든 chat을 한 번의 쿼리로 조회하기 위해서다. `findAllByRootChatId()`로 트리 전체를 가져온 후, 메모리에서 Map으로 구성하여 탐색한다.

```java
// GraphService.java — 트리를 메모리에 로드하여 탐색
List<Chat> allChats = chatRepository.findAllByRootChatIdAndDeletedAtIsNull(rootChatId);
Map<Long, Chat> chatMap = allChats.stream()
        .collect(Collectors.toMap(Chat::getId, c -> c));
Map<Long, List<Chat>> branchPointMap = allChats.stream()
        .filter(c -> c.getBranchPointTurnId() != null)
        .collect(Collectors.groupingBy(Chat::getBranchPointTurnId));
```

**Cascade soft delete**는 재귀로 구현한다.

```java
// ChatService.java — 자손 chat을 재귀적으로 soft delete
private void softDeleteCascade(Chat chat) {
    chat.softDelete();
    List<Chat> children = chatRepository.findAllByParentId(chat.getId());
    for (Chat child : children) {
        if (child.getDeletedAt() == null) {
            softDeleteCascade(child);
        }
    }
}
```

### 4.5 Cursor 기반 페이징

턴 목록 조회에서 Offset이 아닌 **Cursor 방식**을 사용한다. `turn_sequence`를 커서로 사용하여 "이 sequence 이전/이후 N개"를 조회한다.

```java
// TurnService.java — limit + 1 조회로 hasMore 판단
PageRequest pageRequest = PageRequest.of(0, limit + 1);
List<Turn> turns = turnRepository
    .findByChatIdAndTurnSequenceLessThanOrderByTurnSequenceDesc(chatId, lastTurnSequence, pageRequest);

boolean hasMore = turns.size() > limit;
if (hasMore) {
    turns = turns.subList(0, limit);
}
```

Offset 페이징 대비 장점: 데이터 추가/삭제 시 페이지가 밀리지 않고, 인덱스를 활용한 범위 조회라 대량 데이터에서도 성능이 일정하다.

### 4.6 SSE 비동기 처리 (Virtual Thread + TransactionTemplate)

Spring MVC의 `SseEmitter`를 반환하면 HTTP 응답이 열린 채로 유지된다. LLM 호출은 **Virtual Thread**에서 비동기로 수행하며, Virtual Thread 내에서는 `@Transactional`이 동작하지 않으므로 **`TransactionTemplate`**으로 수동 트랜잭션을 관리한다.

```java
// MessageService.java — Virtual Thread 내 수동 트랜잭션
Thread.startVirtualThread(() -> {
    // ... SSE 스트리밍 중 ...

    // 완료 시 DB 저장 (TransactionTemplate으로 별도 트랜잭션)
    transactionTemplate.executeWithoutResult(status -> {
        Message message = messageRepository.findById(aiMessageId).orElseThrow();
        message.updateContent(fullContent);
        message.updateStatus(MessageStatus.COMPLETED);
    });
});
```

**취소 동시성 처리**: `ConcurrentHashMap<Long, StreamingContext>`에 스트리밍 중인 메시지를 등록하고, `AtomicBoolean` 플래그로 cancel 시그널을 전달한다.

```java
// StreamingContext — cancel 경합 해결
record StreamingContext(AtomicBoolean canceled, StringBuffer contentBuffer) {}

// cancel 요청 시
streamCtx.canceled().set(true);

// 스트리밍 루프에서 매 chunk마다 확인
if (streamCtx.canceled().get()) {
    throw new CancelException();
}
```

### 4.7 LLM 클라이언트 인터페이스 분리

```java
// LlmClient.java — 인터페이스
public interface LlmClient {
    void streamCompletion(String model, String userMessage, Consumer<String> onChunk);
}

// MockLlmClient.java — 개발용 Mock 구현체 (@Component)
@Component
public class MockLlmClient implements LlmClient {
    @Override
    public void streamCompletion(String model, String userMessage, Consumer<String> onChunk) {
        for (String chunk : CHUNKS) {
            Thread.sleep(200);
            onChunk.accept(chunk);
        }
    }
}
```

인터페이스로 분리하여 실제 LLM API 연동 시 `MockLlmClient` 대신 새 구현체를 `@Component`로 등록하면 된다. Service 코드 변경 없이 구현체만 교체 가능하다 (DIP).

### 4.8 Converter 분리

Entity → DTO 변환 로직을 별도 `Converter` 클래스로 분리한다. Service에서 비즈니스 로직과 응답 변환을 섞지 않는다.

```java
// ChatConverter.java — Entity를 DTO로 변환
public static ChatResDto.Detail toDetail(Chat chat) {
    return ChatResDto.Detail.builder()
            .chatId(chat.getId())
            .title(chat.getTitle())
            .titleStatus(chat.getTitleStatus())
            // ...
            .build();
}
```

대화 목록 조회 시, N+1 문제를 방지하기 위해 턴 수와 최신 메시지를 **batch 쿼리**로 미리 조회한 후 Converter에 Map으로 전달한다.

```java
// ChatService.getChatList() — batch 쿼리로 N+1 방지
Map<Long, Long> turnCounts = turnRepository.countByChatIds(chatIds).stream()
        .collect(Collectors.toMap(r -> (Long) r[0], r -> (Long) r[1]));
Map<Long, Message> latestMessages = messageRepository.findLatestByChatIds(chatIds).stream()
        .collect(Collectors.toMap(m -> m.getTurn().getChat().getId(), m -> m));
return ChatConverter.toPageInfo(page, turnCounts, latestMessages);
```

### 4.9 DTO 설계 (record + inner class)

요청/응답 DTO는 Java `record`로 정의하고, 하나의 도메인에 대한 DTO를 outer class 안에 inner record로 그룹화한다.

```java
// ChatReqDto.java — 요청 DTO 그룹
public class ChatReqDto {
    public record Create(String title, LlmProvider llmProvider, LlmModel llmModel) {}
    public record BranchCreate(Long branchPointTurnId, String title) {}
    public record UpdateTitle(String title) {}
}

// ChatResDto.java — 응답 DTO 그룹
public class ChatResDto {
    public record Detail(Long chatId, Long rootChatId, ...) {}
    public record ListItem(Long chatId, String title, ...) {}
    public record PageInfo(List<ListItem> content, int page, ...) {}
}
```

### 4.10 JPA 인덱스 선언

엔티티 클래스의 `@Table(indexes = {...})`에 인덱스를 선언하여, `ddl-auto: update` 시 자동으로 인덱스가 생성된다.

```java
// Chat.java
@Table(name = "chat", indexes = {
    @Index(name = "idx_chat_root_deleted", columnList = "root_chat_id, deleted_at"),
    @Index(name = "idx_chat_parent", columnList = "parent_id"),
    @Index(name = "idx_chat_branch_point", columnList = "branch_point_turn_id")
})
```

각 인덱스의 목적:
- `idx_chat_root_deleted`: 같은 트리의 활성 chat 전체 조회 (GraphService)
- `idx_chat_parent`: 자손 chat 조회 (cascade 삭제)
- `idx_chat_branch_point`: 특정 turn에서 분기된 chat 검색
- `idx_turn_chat_sequence`: chat 내 turn 순서 조회 (cursor 페이징, 그래프 탐색)
- `idx_message_turn`: turn에 속한 메시지 조회

---

## 5. 패키지 구조

```
com.aistar.backend
├── domain
│   ├── auth
│   │   ├── controller/AuthController.java
│   │   ├── service/AuthService.java
│   │   ├── dto/AuthReqDto.java, AuthResDto.java
│   │   └── converter/AuthConverter.java
│   ├── chat
│   │   ├── controller/
│   │   │   ├── ChatController.java      ─ 대화 CRUD + 분기 생성
│   │   │   ├── MessageController.java   ─ 메시지 송신/취소/재생성/수정
│   │   │   └── GraphController.java     ─ 그래프 조회/확장
│   │   ├── service/
│   │   │   ├── ChatService.java         ─ 대화 생성/목록/삭제, 분기 생성
│   │   │   ├── MessageService.java      ─ SSE 스트리밍, 취소, 재생성, 수정, 분기점 결정
│   │   │   ├── TurnService.java         ─ 턴 cursor 페이징 조회
│   │   │   └── GraphService.java        ─ 그래프 UP/DOWN 탐색, frontier 계산
│   │   ├── entity/
│   │   │   ├── Chat.java                ─ 트리 구조 (parentId, rootChatId, branchPointTurnId)
│   │   │   ├── Turn.java                ─ 대화 턴 (chat 소속, sequence 순서)
│   │   │   └── Message.java             ─ 메시지 (turn 소속, USER/ASSISTANT)
│   │   ├── enums/
│   │   │   ├── TitleStatus.java         ─ PENDING, GENERATED, USER_EDITED
│   │   │   ├── MessageStatus.java       ─ STREAMING, COMPLETED, CANCELED, FAILED
│   │   │   ├── SenderType.java          ─ USER, ASSISTANT
│   │   │   ├── LlmProvider.java         ─ OPENAI, GOOGLE, ANTHROPIC
│   │   │   ├── LlmModel.java           ─ gpt-4o-mini, gemini-2.0-flash, claude-3.5-sonnet
│   │   │   └── CursorDirection.java     ─ BACKWARD, FORWARD
│   │   ├── dto/
│   │   │   ├── ChatReqDto.java          ─ Create, BranchCreate, UpdateTitle
│   │   │   ├── ChatResDto.java          ─ Detail, ListItem, PageInfo
│   │   │   ├── MessageReqDto.java       ─ Send
│   │   │   ├── MessageResDto.java       ─ TurnStarted, Chunk, TurnCompleted, Cancelled, ...
│   │   │   ├── TurnResDto.java          ─ TurnPage, TurnDetail, MessageDetail
│   │   │   └── GraphResDto.java         ─ GraphResult, ExpandResult, TurnNodeDto, ChatNodeDto
│   │   ├── converter/
│   │   │   ├── ChatConverter.java
│   │   │   └── TurnConverter.java
│   │   └── repository/
│   │       ├── ChatRepository.java
│   │       ├── TurnRepository.java
│   │       └── MessageRepository.java
│   ├── llm
│   │   └── client/
│   │       ├── LlmClient.java           ─ 인터페이스 (streamCompletion)
│   │       └── MockLlmClient.java       ─ 테스트용 Mock 구현체
│   └── member
│       ├── controller/MemberController.java
│       ├── service/MemberService.java
│       ├── entity/Member.java
│       └── repository/MemberRepository.java
├── global
│   ├── apiPayload/                      ─ 공통 응답(ApiResponse), 에러 코드, 예외 처리
│   ├── config/
│   │   ├── AppConfig.java
│   │   ├── SwaggerConfig.java
│   │   └── DataInitializer.java         ─ 개발용 초기 데이터
│   ├── entity/BaseEntity.java           ─ createdAt, updatedAt 공통
│   └── security/
│       ├── SecurityConfig.java          ─ CORS, JWT 필터, 인가 설정
│       ├── JwtTokenProvider.java
│       ├── JwtAuthenticationFilter.java
│       ├── JwtAuthenticationEntryPoint.java
│       ├── CustomUserDetails.java
│       └── CustomUserDetailsService.java
└── BackendApplication.java
```

---

## 6. Phase 3 커밋 이력

| 커밋 | 설명 |
|------|------|
| `152fc18` | 그래프 조회 API — GraphService UP/DOWN 탐색, frontier 계산 |
| `09f6a37` | 그래프 확장 API — expandWindow, 방향별 frontier |
| `da25c3d` | 응답 재생성 API — regenerateMessage, resolveBranchPoint |
| `2d6cd01` | 메시지 수정 API — editMessage, createBranchTurn 공통화 |
| `18fa720` | title nullable 정책 변경 — DB 기본 문구 제거, ERD/명세 동기화 |

---

## 7. ERD 주요 변경 (Phase 2 → Phase 3)

```sql
-- chat.title: NOT NULL DEFAULT '제목없음' → NULL
ALTER TABLE chat MODIFY COLUMN title TEXT NULL;

-- title_status 컬럼 추가
-- enum('PENDING','GENERATED','USER_EDITED') NOT NULL DEFAULT 'PENDING'

-- turn.summary: TEXT NULL (DEFAULT 제거)

-- 인덱스 추가
CREATE INDEX idx_chat_root_deleted  ON chat(root_chat_id, deleted_at);
CREATE INDEX idx_chat_parent        ON chat(parent_id);
CREATE INDEX idx_chat_branch_point  ON chat(branch_point_turn_id);
CREATE INDEX idx_turn_chat_sequence ON turn(chat_id, turn_sequence);
CREATE INDEX idx_message_turn       ON message(turn_id);
```

---

## 8. 미구현/보류 사항

| 항목 | 사유 |
|------|------|
| `UsageRecord` 엔티티 | ERD에 정의되어 있으나 Phase 3까지 필요한 기능 없음 |
| `POST /chats/{chatId}/restore` | 복구 API — 우선순위 낮음으로 보류 |
| LLM 실제 연동 | MockLlmClient 사용 중, 실제 API 키 연동은 별도 진행 |
