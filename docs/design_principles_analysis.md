# AIT Backend — 설계 원리 관점에서의 프로젝트 분석

> 출처 강의: `6. 설계 원리.pdf`, `6. 설계 원리-II.pdf`, `7. Architecture Design and Pattern.pdf`
> 분석 대상: `com.aistar.backend` (Spring Boot 3, Java 21, JWT + SSE + JPA)
> 작성일: 2026-05-27

본 문서는 강의에서 다룬 **디자인 원리(추상화/모듈화/결합도/응집도/SOLID/패키지 원칙)** 와 **아키텍처 스타일·디자인 패턴** 의 관점에서 현재 백엔드 프로젝트를 진단하고, 부족한 점과 리팩토링 후보를 정리한 문서이다.

---

## 1. 개요 — "What → How" 단계의 현재 위치

강의 §1에서 강조된 것처럼 디자인은 **고객의 What이 아니라 개발자의 How** 를 다루는 단계이다.

- 본 프로젝트는 이미 `docs/phase{1..4}_document/` 에서 요구분석 → API 명세 → ERD 까지 완료되어 있다 (요구분석 phase).
- 코드 단계는 "어떻게 만들 것인가" 즉 **모듈/컴포넌트로 잘라서, 결합은 낮고 응집은 높게 만들어 놓았는가?** 가 평가 기준이 된다.

이 문서는 그 평가의 metric 을 강의에 정의된 항목별로 매긴다.

---

## 2. 시스템 아키텍처 (Architecture)

### 2.1 채택된 아키텍처 스타일

강의 §7에서 정리한 스타일 목록 중 본 프로젝트가 사용하는 조합:

| 스타일 | 어디서 나타나는가 |
|---|---|
| **Layered Architecture** | Controller → Service → Repository → DB 의 4계층. Spring MVC 의 정석. |
| **Client-Server (Three-tier)** | Frontend(React) ↔ Backend(Spring) ↔ MySQL. 추가로 외부 AI Server(LLM)를 분리한 4-tier 변형. |
| **Event-driven (부분)** | SSE 기반 메시지 스트리밍 (`MessageController.sendMessage` → `streamMessage`). chunk 이벤트를 비동기로 흘려보내는 구조. |
| **MVC (변형)** | Spring MVC. Model = JPA Entity, View = JSON DTO, Controller = `*Controller`. |
| **Layered 내 Façade** | `ApiResponse<T>` 가 응답 형식을 단일 진입점으로 통합. 강의에서 말한 "복잡한 내부 구조를 일관된 interface 로 가린다" 와 일치. |

### 2.2 컴포넌트 다이어그램 (개념적)

```
┌─────────────────────────────────────────────────────────────┐
│                       React Client                          │
└───────────────────────────┬─────────────────────────────────┘
                            │ REST + SSE (JWT)
┌───────────────────────────▼─────────────────────────────────┐
│  com.aistar.backend (Spring Boot)                           │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ global                                              │    │
│  │  ├─ security   (JWT filter, UserDetails)            │    │
│  │  ├─ apiPayload (ApiResponse, ErrorStatus, Handler)  │    │
│  │  ├─ config     (Swagger, App, Health)               │    │
│  │  └─ entity     (BaseEntity – created/updatedAt)     │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                             │
│  ┌─────────────┐  ┌─────────────┐  ┌──────────────────┐     │
│  │ domain.auth │  │domain.member│  │  domain.usage    │     │
│  └─────────────┘  └─────────────┘  └──────────────────┘     │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ domain.chat                                         │    │
│  │  ├─ Chat / Turn / Message (entity)                  │    │
│  │  ├─ ChatService                                     │    │
│  │  ├─ MessageService    (SSE 스트리밍, 분기/취소/재생성)│   │
│  │  ├─ ContextAssembler  (맥락 조립 + 압축)             │    │
│  │  ├─ GraphService      (UP/DOWN 그래프 탐색)          │    │
│  │  └─ ExplorerService   (커서 기반 목록)               │    │
│  └─────────────────────────────────────────────────────┘    │
│                          │ 인터페이스 의존                   │
│  ┌───────────────────────▼─────────────────────────────┐    │
│  │ ai                                                  │    │
│  │  ├─ AiServerClient   (interface)                    │    │
│  │  └─ AiServerWebClient (WebClient 구현)              │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ domain.llm  ⚠ (LlmClient + MockLlmClient — 死 코드) │    │
│  └─────────────────────────────────────────────────────┘    │
└───────────────────┬─────────────────────────────┬───────────┘
                    │ JDBC                        │ HTTP/SSE
              ┌─────▼─────┐                ┌──────▼────────┐
              │   MySQL   │                │  External AI  │
              └───────────┘                │     Server    │
                                           └───────────────┘
```

### 2.3 모듈 vs 컴포넌트 — 본 프로젝트에서의 매핑

강의 §6 정리:
- **모듈**: 코드 레벨의 관심사 분리 단위 (재사용·중복 감소). static 관점.
- **컴포넌트**: 실행/배포 단위 (독립 배포 가능). runtime 관점.

본 프로젝트는 **단일 모놀리식 JAR (1 컴포넌트)** 이지만 내부적으로 **`domain.*` 패키지를 사실상 "논리적 모듈"** 로 사용한다. 강의 §6의 "서브시스템 = 패키지" 정의에 정확히 부합한다.

> 핵심 분리축: 외부 AI 서버는 별도 컴포넌트(다른 프로세스)다. → `ai.AiServerClient` 인터페이스가 컴포넌트 간 계약(façade)이 된다.

---

## 3. 모듈 분할의 근거 (Subsystem)

> 강의 강조: "왜 이렇게 나눴어요?" 라는 질문에 답할 수 있어야 한다.

| 패키지 | 분할 근거 (강의 metric 기준) |
|---|---|
| `global` | 도메인과 무관한 공통 인프라(Auth, 예외, 응답 포맷). **CCP** — "동일한 이유로 함께 변하는 것" (인증·응답 표준은 한 곳에서만 바뀐다). |
| `domain.auth` | 회원가입/로그인 유스케이스. 사용자 자격증명 흐름이 한 곳에 응집. |
| `domain.member` | Member 엔티티 + 내정보. auth가 자격증명을 본다면 member 는 프로필을 본다 — 변경 이유가 다름. |
| `domain.chat` | Chat/Turn/Message 는 **존재 의존성이 강함**(Turn은 Chat 없이 의미 없음). 강의의 "데이터를 공유하는 것은 같이 묶는다 = communicational cohesion" 에 부합. |
| `domain.llm` | LLM 추상화 레이어 — **DIP 적용을 위한 자리**. ⚠ 단, 현재는 사용되지 않음. |
| `domain.usage` | 사용량/Plan — 결제·과금 분리를 위한 사전 준비. SRP. |
| `ai` | 외부 AI 서버 어댑터(다른 프로세스 통신). 컴포넌트 경계. |

`package_by_domain.md` 에 정해진 패키지 가이드와 실제 코드가 거의 일치한다 → 명세 기반 설계가 잘 지켜진 편.

---

## 4. 결합도 (Coupling) 분석

### 4.1 결합도 종류와 본 프로젝트의 위치

강의 §6 결합도 5단계 (낮음 ← Data < Stamp < Control < Common < Content → 높음):

- **대부분의 Service ↔ Repository 호출은 Data Coupling**: `chatRepository.findById(...)` — 단순 식별자/엔티티만 주고받음. ✅
- **Stamp Coupling** 존재: `MessageService.streamMessage(TurnContext ctx)` 가 `TurnContext` 전체를 받지만 일부만 쓰는 코드 경로가 있음 (cancel 분기에서 `ctx.aiMessage().getId()` 만 필요). 큰 문제는 아님.
- **Common Coupling 위험 1곳**: `MessageService.streamingContexts (ConcurrentHashMap<Long, StreamingContext>)` — 인스턴스 전역 mutable state. **다중 인스턴스 배포 시 cancel 신호가 전달 안 됨** (스케일 아웃 불가능).
- **Content Coupling 없음** ✅ (다른 클래스의 내부 상태를 직접 건드리는 곳은 발견되지 않음).

### 4.2 모듈 간 의존 그래프 (의심 영역)

| 의존 | 평가 |
|---|---|
| `MessageService` → `ChatService` | 같은 도메인 내 service-to-service 직접 호출 → `touchAncestorChain` 한 메서드 때문에 결합. **하향 결합도(Ce)** 가 늘어남. |
| `MessageService` → `ai.AiServerClient` (interface) | ✅ DIP 정상. |
| `MessageService` → `usage.UsageService` | cross-domain 직접 호출. **응집 위반 가능성** — 메시지 도메인이 사용량을 직접 누적시키는 책임을 가진다. Event/Observer 로 분리하면 결합 ↓. |
| `ChatService` → `MemberRepository` | 도메인 경계를 넘는 repo 접근. 작은 위반이지만 `MemberService` 를 두지 않고 직접 참조 → CBO 증가. |
| `ContextAssembler` → `ChatRepository`, `TurnRepository` | 정상. 단방향. |

### 4.3 CBO / Ca / Ce 추정 (강의 §6 metric)

대표 클래스 기준 거칠게:

| 클래스 | Ce (사용함) | Ca (사용됨) | 평가 |
|---|---|---|---|
| `MessageService` | ≥ 10 | 1 (Controller) | **Ce 과다** — refactoring 후보 #1 |
| `ChatService` | 5 | 3 | 양호 |
| `ContextAssembler` | 4 | 1 | 양호 |
| `GraphService` | 4 | 1 | 양호 |
| `AiServerClient` (interface) | 0 | 2 | 안정 모듈 (I≈0). ✅ |
| `BaseEntity` | 0 | many | 안정. ✅ |

→ `MessageService` 가 가장 강한 refactoring 대상.

---

## 5. 응집도 (Cohesion) 분석

강의 §6 응집도 7단계 (낮음 ← Coincidental < Logical < Temporal < Procedural < Communicational < Sequential < Functional → 높음):

| 모듈 | 응집 유형 | 근거 |
|---|---|---|
| `ChatService` | **Functional** ✅ | "대화 자체에 대한 CRUD + 소유권 검증" 한 가지 목적. |
| `ChatRepository`, `TurnRepository`, `MessageRepository` | Functional ✅ | 각 엔티티에 대한 영속화. |
| `ContextAssembler` | Functional ✅ | "LLM 호출 전 맥락 조립 + 압축" 단일 목적 (§3.1/§3.2 명세에 1:1 대응). |
| `GraphService` | Functional 이지만 **크기 과다** | UP/DOWN/Frontier/Expand 모두 한 클래스 — 분리 여지. |
| **`MessageService`** | **Communicational + Procedural 혼합** ⚠ | Turn/Message 생성 + SSE 스트리밍 + cancel + regenerate + edit + summary 비동기 → "메시지" 라는 데이터를 공유하기만 하는 묶음. 책임이 6개 가까이 됨. |
| `global.apiPayload.ErrorStatus` | **Logical** | 모든 에러 코드를 하나의 enum 으로 묶음. 강의에서 logical cohesion 은 약한 응집이라 했지만, 이건 의도된 카탈로그(REP/CCP 측면 OK). |

> 가장 큰 문제: **`MessageService` 의 SSE 스트리밍 책임이 비대**. 강의가 말한 "Functional Cohesion 이 가장 바람직" 원칙에 어긋남.

---

## 6. SOLID 적용 현황

### 6.1 SRP — Single Responsibility Principle

| 클래스 | 평가 |
|---|---|
| `ChatService` | ✅ 잘 지킴 |
| `MessageService` | ❌ **6가지 책임**: ① Turn/Message 영속화 ② SSE emitter 관리 ③ 스트리밍 컨텍스트(cancel state) 관리 ④ 분기 생성 ⑤ regenerate/edit 흐름 ⑥ summary 비동기. → 분리 필요. |
| `AiServerWebClient` | ✅ 외부 호출 + 파싱만. |
| `ApiResponse` | ✅ |
| 엔티티 `Chat` | ✅ — 변경 메서드(`softDelete`, `updateTitle` 등)가 자기 상태만 다룸. |

### 6.2 OCP — Open/Closed Principle

- ✅ `LlmModel` 추가 시 기존 코드 수정 없이 enum 항목만 늘리면 됨.
- ✅ `BaseErrorCode` interface — 새 도메인 에러 코드 enum 을 추가해도 `GlobalExceptionHandler` 는 그대로.
- ⚠ `MessageService.streamMessage` 의 try/catch 안에 모든 흐름이 있어 새로운 종류의 SSE 이벤트(예: `tool_call`)를 추가하려면 핵심 메서드를 직접 수정해야 함 → OCP 부분 위반.

### 6.3 LSP — Liskov Substitution

- 인터페이스 구현 클래스가 부모 계약을 부수는 사례 미발견. `AiServerClient` ↔ `AiServerWebClient` 정상.
- `MockLlmClient` 는 `LlmClient` 구현이지만 **호출되지 않는 죽은 인터페이스** — LSP 적용을 검증할 수 없는 상태.

### 6.4 ISP — Interface Segregation

- `AiServerClient` 가 5개 메서드(`streamChatCompletion`, `complete`, `isAvailable`, `generateSummary`, `generateBranchTitle`)를 가짐. 호출자별로 보면 **MessageService 는 stream 1개만** 쓰고 **HealthController 는 isAvailable 만** 쓰는데 모두 같은 fat interface 를 주입받음.
- 강의 §6 정의로는 "Fat / Polluted Interface" 에 살짝 해당.
- → `StreamingLlm`, `LlmHealthCheck`, `LlmCompletion` 등으로 분리 가능 (Refactoring #5 참고).

### 6.5 DIP — Dependency Inversion

- ✅ **잘 됨**: `MessageService` → `AiServerClient` (interface) → `AiServerWebClient` (구현). 강의 슬라이드의 Car/Tire 예시와 동일 패턴.
- ✅ Spring 의 DI 자체가 강의에서 말한 "Framework 이 제어를 가져간다 (IoC)" 와 동일.
- ⚠ 단, `domain.llm.client.LlmClient` 추상화는 사실상 미사용. 추상화 레이어가 두 개(`LlmClient`, `AiServerClient`)로 분기된 상태.

---

## 7. 디자인 패턴 적용 현황

강의 §7 의 GoF 패턴을 본 프로젝트에서 추적:

| 패턴 | 적용 위치 |
|---|---|
| **Façade** | `ApiResponse<T>`, `GlobalExceptionHandler` — 내부 복잡성 숨김. |
| **Strategy (잠재적)** | `AiServerClient` 인터페이스 — 다른 LLM 구현으로 갈아끼울 수 있게 설계. |
| **Builder** | Lombok `@Builder` — 모든 엔티티/DTO에서 사용. |
| **Singleton** | Spring Bean (`@Service`, `@Component`) — 모든 서비스가 사실상 싱글톤. |
| **Adapter** | `AiServerWebClient` — 외부 AI 서버의 SSE 형식을 우리 도메인의 `Consumer<String> onChunk` 로 변환. |
| **Template Method (약)** | `BaseEntity` 의 createdAt/updatedAt 공통 처리. |
| **Iterator** | Spring Data 의 `Page<Chat>`, `Stream` — 자료구조에 종속되지 않는 순회. |
| **Observer (잠재적, 미적용)** | 메시지 완료 시 usage 누적, summary 생성 → **현재는 직접 호출**. Observer 로 분리하면 결합 ↓ (Refactoring #3). |
| **State (잠재적, 미적용)** | `MessageStatus` enum (STREAMING/COMPLETED/CANCELED/FAILED) — 현재는 `switch` 로 분기. 상태 전이가 많아지면 State Pattern 후보. |
| **Factory** | 명시적 사용 없음. |

---

## 8. 디자인 원리 — Abstraction / Encapsulation / Information Hiding

### 8.1 Abstraction (강의 §6)
- `LlmModel` enum 의 `getContextWindow()` — 모델별 컨텍스트 윈도우 추상화. ✅
- DTO ↔ Entity 분리 — API 표현과 영속 표현의 분리. ✅

### 8.2 Encapsulation / Information Hiding
- Entity 의 `@AllArgsConstructor(access = AccessLevel.PRIVATE)`, `@NoArgsConstructor(access = AccessLevel.PROTECTED)` — 외부에서 직접 생성 차단. ✅
- 상태 변경은 명시적 메서드(`updateTitle`, `softDelete`, `touchLastActivityAt`)로만. ✅
- ⚠ 반대로 `MessageService.streamingContexts` 는 `private final Map` 이지만 내부 `AtomicBoolean canceled` 가 직접 노출됨. record 안에 들어있어 외부에서 set 가능. → **단순 캡슐화 위반은 아니나 mutable shared state** 라는 점에서 위험.

### 8.3 Interface Separation
- Controller ↔ Service ↔ Repository 의 3계층이 인터페이스 또는 명시적 메서드 경계로 잘 분리. ✅
- 단, **Service 간 직접 호출**(`MessageService` → `ChatService`, `MessageService` → `UsageService`)이 늘면 facade 계층(예: `MessagingFacade`) 도입 검토.

---

## 9. 패키지 설계 원칙 (REP / CCP / CRP)

강의 §6-II 의 3원칙:

| 원칙 | 본 프로젝트 |
|---|---|
| **REP** (Reuse/Release Equivalence) | 단일 jar 로 묶여 함께 릴리즈됨. domain 별로 분리 배포는 안 함 — 모놀리식이므로 자연스러움. |
| **CCP** (Common Closure — 같은 이유로 변하면 같이 묶기) | ✅ `ErrorStatus` enum, `BaseEntity`, `ApiResponse` 가 잘 만족. 단 `domain.chat.enums.LlmModel/LlmProvider` 는 LLM 도메인 변경 시 같이 바뀌므로 **`domain.llm.enums` 로 옮기는 게 CCP 에 더 맞음** (Refactoring #6). |
| **CRP** (Common Reuse — 같이 쓰는 것은 같이 묶기) | ✅ DTO 가 `*ReqDto`, `*ResDto` inner-record 로 그룹화되어 잘 묶임. |

강의 §6-II 정리: **REP/CCP 는 "뭘 넣어야 하는가", CRP 는 "뭘 빼야 하는가"**. 본 프로젝트는 CRP 측면에서는 충분히 빼는 데 신경 썼지만, CCP 관점에서 `chat` 패키지가 LLM 관련 enum 까지 끌어안고 있어 약간 부풀어 있음.

---

## 10. 품질 속성 (ISO 25010) 평가

| 품질 | 현황 |
|---|---|
| **Reliability** | SSE 실패 시 `MessageStatus.FAILED` 로 전이, partial content 보존 — ✅ |
| **Performance Efficiency** | Virtual Thread (`Thread.startVirtualThread`) 사용으로 동시 스트리밍 처리 효율 ↑ ✅ |
| **Maintainability** | 도메인 패키지화 양호. 단 `MessageService` 비대로 인지 복잡도 ↑ → ⚠ |
| **Portability** | DB는 JPA 추상화로 비교적 자유롭게 교체 가능. AI 서버도 인터페이스 분리로 교체 가능 ✅ |
| **Security** | JWT + Spring Security + BCrypt + STATELESS 세션 + CORS 화이트리스트. ✅ |
| **Compatibility** | REST + SSE 표준 사용. ✅ |
| **Usability (개발자)** | Swagger 통합 ✅ |

---

## 11. 부족한 점 / 개선 제안 / Refactoring 후보

> 강의의 metric 으로 본 우선순위. 1~3 은 영향 큼, 4 이하는 점진 개선.

### Refactoring #1 — `MessageService` 분해 (SRP, Cohesion, OCP)
**문제**: 518줄에 책임 6개. Communicational + Procedural 응집.
**제안 분해**:
```
MessageService            ← Turn/Message 생성, 소유권 검증, 분기 생성만
  ├─ MessageStreamingService  ← SSE emitter + ctxHolder + AI 호출
  ├─ StreamingRegistry        ← streamingContexts Map 관리
  ├─ MessageLifecycleService  ← cancel/regenerate/edit 의 상태 전이
  └─ TurnSummaryService       ← 비동기 summary 생성
```
- 효과: 각 클래스 Functional Cohesion 으로 끌어올림.
- OCP: 새 SSE 이벤트(`tool_call` 등) 추가 시 `MessageStreamingService` 만 변경.

### Refactoring #2 — `domain.llm` 모듈 정리 또는 폐기 (DIP, 死 코드)
**문제**: `LlmClient`, `MockLlmClient` 가 어디서도 주입되지 않음. `ai.AiServerClient` 와 추상화가 중복.
**선택**:
- **(A) 폐기**: `domain.llm` 패키지 제거 + `ai` 를 `domain.llm.adapter` 로 이동 → 도메인 일관성 ↑.
- **(B) 통합**: `LlmClient` 를 도메인 계약으로 살리고 `AiServerWebClient` 가 그 구현이 되도록 정리. `MessageService` 는 `LlmClient` 만 알게 함.

→ B 가 강의 §6 의 DIP 슬라이드(Car ↔ Tire interface) 와 더 일치.

### Refactoring #3 — Usage 누적과 Summary 생성을 Observer 로 (결합 ↓)
**문제**: `MessageService` 가 사용량 누적과 summary 생성까지 직접 호출 → 다른 도메인을 자기가 진행.
**제안**: Spring `ApplicationEventPublisher` 로 `MessageCompletedEvent` 발행 → `UsageService`, `SummaryGenerator` 가 `@EventListener(@Async)` 로 처리.
- 결합도: Common/Stamp → 무결합(데이터 페이로드만 공유).
- 강의의 Observer Pattern 슬라이드와 1:1 대응.

### Refactoring #4 — `streamingContexts` 의 분산 환경 대응
**문제**: 인스턴스 메모리에 저장 → 다중 인스턴스 시 cancel 신호 전달 불가.
**제안**:
- 단기: 그대로 두되 sticky session / 단일 인스턴스 명시.
- 장기: Redis Pub/Sub 로 cancel 시그널 브로드캐스트.

### Refactoring #5 — `AiServerClient` 분리 (ISP)
**문제**: 5 메서드 fat interface. Health 만 보는 곳, stream 만 보는 곳이 모두 동일 의존.
**제안**:
```java
interface LlmStreamClient   { void streamChatCompletion(...); }
interface LlmCompletionClient{ AiCompletionResponse complete(...); ... }
interface LlmHealthCheck    { boolean isAvailable(); }
```
구현 클래스는 셋 다 implements. 호출자는 자기가 쓰는 것만 주입.

### Refactoring #6 — `LlmModel`/`LlmProvider` 위치 이동 (CCP)
**문제**: LLM 모델 추가/변경은 LLM 도메인 변경 이유인데, enum 이 `domain.chat.enums` 에 있어 chat 패키지가 같이 변경됨.
**제안**: `domain.llm.enums.LlmModel`, `LlmProvider` 로 이동. chat 도메인은 import 만 함.

### Refactoring #7 — `GraphService` 분해
**문제**: UP traversal, DOWN BFS, Frontier 계산, Window expand 가 한 클래스. 369줄.
**제안**:
```
GraphService           ← public API (getGraph, expandWindow)
  ├─ TurnTraverser     ← traceUp / traceDown
  ├─ FrontierCalculator
  └─ GraphNodeMapper   ← toChatNodeDto, toTurnNodeDto
```

### Refactoring #8 — Soft delete cascade 의 N+1 위험
`ChatService.softDeleteCascade` 가 재귀로 `findAllByParentId` 호출. 분기 깊이가 깊어지면 쿼리 폭증.
→ root 의 모든 후손을 1쿼리로 가져와 메모리에서 트리 구성 후 일괄 update.

### Refactoring #9 — Stamp Coupling 줄이기
`createTurnAndMessages` 가 `TurnContext` 통째로 반환 → `streamMessage` 가 전체 객체를 받음.
→ 필요한 필드만 받는 method-level parameter 또는 `record` 분리로 의도 명확화.

### Refactoring #10 — 토큰 추정 (`estimateTokens`) 의 정확성
`text.length() / 4` 의 단순 추정. 한국어/영어 비율, 모델별 tokenizer 차이를 무시.
→ 모델별 `Tokenizer` Strategy 도입 (Strategy Pattern). 강의 §7 Strategy 예시와 일치.

---

## 12. 잘 된 점 (Strength) — 그대로 유지할 것

- ✅ 도메인별 패키지 분할이 `package_by_domain.md` 명세와 정확히 일치 — Spec-driven.
- ✅ 인터페이스(`AiServerClient`)를 통한 외부 시스템 격리 — DIP 의 모범.
- ✅ JPA Entity 의 strict 캡슐화 (생성자 가시성 제한, 변경 메서드만 노출).
- ✅ `ApiResponse` + `ErrorStatus` 의 통합 응답/에러 카탈로그 — Façade + CCP.
- ✅ JWT + STATELESS + CORS 화이트리스트 — 보안 베이스라인 충족.
- ✅ Soft delete + cascade — 데이터 보존성 ↑.
- ✅ Virtual Thread 활용으로 SSE 동시성 효율 확보.
- ✅ Spring Event(미사용)/Observer 패턴을 도입할 여지를 남겨둔 깔끔한 도메인 분할.

---

## 13. 정리 — 강의 metric 기준 종합 평가

| 항목 | 평가 | 핵심 근거 |
|---|---|---|
| 추상화 | A | LlmModel, DTO/Entity 분리, Repository 추상화 |
| 모듈화 | B+ | 패키지는 깔끔하나 `MessageService`/`GraphService` 비대 |
| Information Hiding | A− | Entity 캡슐화 모범. mutable shared map 1곳만 주의 |
| Interface Separation | B | `AiServerClient` fat interface |
| Coupling | B | Service ↔ Service 직접 호출이 결합도 push up |
| Cohesion | B− | `MessageService` 가 Communicational/Procedural 혼재 |
| SRP | B | MessageService 외에는 양호 |
| OCP | B+ | enum + interface 기반 확장은 잘 됨 |
| LSP | A | 위반 없음 |
| ISP | C+ | `AiServerClient` 한 곳 위반 |
| DIP | A− | Spring DI + interface, 단 `domain.llm` 중복 추상화 |
| 아키텍처 적합성 | A | Layered + Client-Server + SSE 적절 조합 |

**한 줄 요약**: 도메인 분할과 외부 의존 추상화는 모범적이다. 다만 채팅의 **스트리밍 책임이 한 서비스에 과집중**되어 있고, **`domain.llm` 패키지의 사용 의도가 모호**하다. Refactoring #1~#3 만 정리해도 강의 metric 기준 전체 등급이 한 단계 올라간다.

---

## 참고: 강의 슬라이드와의 매핑 (자체 점검 체크리스트)

- [x] What vs How 단계 인식 (§1)
- [x] Subsystem = Package 매핑 (§6)
- [x] Module / Component / Allocation 관점 구분 (§6)
- [x] Design Goal & Style 결정 (Layered+Client-Server+SSE)
- [x] Quality Objective (ISO 25010 7항목) 점검
- [x] 결합도 5단계 진단
- [x] 응집도 7단계 진단
- [x] SOLID 5원칙 진단
- [x] REP / CCP / CRP 진단
- [x] 적용된 GoF 패턴 식별
- [x] 미적용/잠재 패턴(Observer, State, Strategy) 식별
- [x] Architecture Evaluation (SAAM/ATAM 관점의 시나리오 = "AI server 가 죽었을 때", "동시 스트리밍 수백건", "분기 깊이 증가 시")
