# AIT Backend — 설계 원리 관점에서의 프로젝트 분석

> 출처 강의: `6. 설계 원리.pdf`, `6. 설계 원리-II.pdf`, `7. Architecture Design and Pattern.pdf`
> 분석 대상: `com.aistar.backend` (Spring Boot 3, Java 21, JWT + SSE + JPA)
> 최초 작성일: 2026-05-27
> 최종 갱신일: 2026-06-03 (Phase 4 리팩토링 + Phase 5 통합 + 쿼리 최적화 반영)

본 문서는 강의에서 다룬 **디자인 원리(추상화/모듈화/결합도/응집도/SOLID/패키지 원칙)** 와 **아키텍처 스타일·디자인 패턴** 의 관점에서 현재 백엔드 프로젝트를 진단하고, 부족한 점과 리팩토링 후보를 정리한 문서이다.

---

## 1. 개요 — "What → How" 단계의 현재 위치

강의 §1에서 강조된 것처럼 디자인은 **고객의 What이 아니라 개발자의 How** 를 다루는 단계이다.

- 본 프로젝트는 이미 `docs/phase{1..5}_document/` 에서 요구분석 → API 명세 → ERD 까지 완료되어 있다 (요구분석 phase).
- 코드 단계는 "어떻게 만들 것인가" 즉 **모듈/컴포넌트로 잘라서, 결합은 낮고 응집은 높게 만들어 놓았는가?** 가 평가 기준이 된다.
- Phase 4 리팩토링에서 이전 분석(2026-05-27)에서 지적된 **Refactoring #1~#7 중 6건을 해소**하였고, 추가로 쿼리 최적화를 수행하였다.

이 문서는 그 평가의 metric 을 강의에 정의된 항목별로 매긴다.

---

## 2. 시스템 아키텍처 (Architecture)

### 2.1 채택된 아키텍처 스타일

강의 §7에서 정리한 스타일 목록 중 본 프로젝트가 사용하는 조합:

| 스타일 | 어디서 나타나는가 |
|---|---|
| **Layered Architecture** | Controller → Service → Repository → DB 의 4계층. Spring MVC 의 정석. |
| **Client-Server (Three-tier)** | Frontend(React) ↔ Backend(Spring) ↔ MySQL. 추가로 외부 AI Server(LLM)를 분리한 4-tier 변형. |
| **Event-driven** | `MessageCompletedEvent` 발행 → `UsageAccumulationListener`, `TurnSummaryListener` 가 비동기 처리. SSE 스트리밍도 이벤트 기반. |
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
│  ┌─────────────┐  ┌─────────────┐  ┌──────────────────┐    │
│  │ domain.auth │  │domain.member│  │  domain.usage    │    │
│  │             │  │             │  │  ├─ UsageService  │    │
│  │             │  │             │  │  └─ Listener ◄────┼─── Event
│  └─────────────┘  └─────────────┘  └──────────────────┘    │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ domain.chat                                         │    │
│  │  ├─ Entity: Chat / Turn / Message                   │    │
│  │  ├─ ChatService           (CRUD + 소유권 검증)       │    │
│  │  ├─ MessageService        (Turn/Message 생성, 분기)  │    │
│  │  ├─ MessageStreamingService (SSE + LLM 호출)        │    │
│  │  ├─ StreamingRegistry     (cancel state 관리)        │    │
│  │  ├─ TurnSummaryService    (비동기 요약/제목 생성)     │    │
│  │  ├─ ContextAssembler      (맥락 조립 + 압축)         │    │
│  │  ├─ GraphService → graph/ (UP/DOWN 그래프 탐색)      │    │
│  │  │   ├─ TurnTraverser                               │    │
│  │  │   ├─ FrontierCalculator                          │    │
│  │  │   └─ GraphNodeMapper                             │    │
│  │  ├─ ExplorerService       (커서 기반 목록)           │    │
│  │  ├─ TurnService           (턴 페이지네이션)          │    │
│  │  ├─ Event: MessageCompletedEvent ──► Listener       │    │
│  │  └─ Listener: TurnSummaryListener                   │    │
│  └─────────────────────────────────────────────────────┘    │
│                          │ 인터페이스 의존 (ISP 분리)        │
│  ┌───────────────────────▼─────────────────────────────┐    │
│  │ domain.llm                                          │    │
│  │  ├─ client/                                         │    │
│  │  │   ├─ LlmStreamClient      (스트리밍)             │    │
│  │  │   ├─ LlmCompletionClient  (동기 완성)            │    │
│  │  │   ├─ LlmHealthClient      (헬스체크)             │    │
│  │  │   ├─ LlmSummaryClient     (요약)                 │    │
│  │  │   ├─ LlmBranchTitleClient (제목 생성)            │    │
│  │  │   └─ AiServerWebClient    (5개 인터페이스 구현)   │    │
│  │  ├─ config/ (AiServerProperties, AiServerConfig)    │    │
│  │  ├─ dto/    (Request/Response records)               │    │
│  │  └─ enums/  (LlmModel, LlmProvider)                │    │
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

> 핵심 분리축: 외부 AI 서버는 별도 컴포넌트(다른 프로세스)다. → `domain.llm.client` 의 5개 인터페이스가 컴포넌트 간 계약(façade)이 된다.

---

## 3. 모듈 분할의 근거 (Subsystem)

> 강의 강조: "왜 이렇게 나눴어요?" 라는 질문에 답할 수 있어야 한다.

| 패키지 | 분할 근거 (강의 metric 기준) |
|---|---|
| `global` | 도메인과 무관한 공통 인프라(Auth, 예외, 응답 포맷). **CCP** — "동일한 이유로 함께 변하는 것" (인증·응답 표준은 한 곳에서만 바뀐다). |
| `domain.auth` | 회원가입/로그인 유스케이스. 사용자 자격증명 흐름이 한 곳에 응집. |
| `domain.member` | Member 엔티티 + 내정보. auth가 자격증명을 본다면 member 는 프로필을 본다 — 변경 이유가 다름. |
| `domain.chat` | Chat/Turn/Message 는 **존재 의존성이 강함**(Turn은 Chat 없이 의미 없음). 강의의 "데이터를 공유하는 것은 같이 묶는다 = communicational cohesion" 에 부합. |
| `domain.llm` | LLM 추상화 레이어. 5개 세분 인터페이스 + 단일 구현 + DTO + enum. **DIP 적용 지점** — 도메인 코드는 인터페이스만 알고, 구현은 인프라 쪽에서 주입. |
| `domain.usage` | 사용량/Plan — 결제·과금 분리를 위한 사전 준비. SRP. Event Listener 로 chat 도메인과 느슨하게 결합. |

`package_by_domain.md` 에 정해진 패키지 가이드와 실제 코드가 일치한다 → 명세 기반 설계가 잘 지켜진 편.

---

## 4. 결합도 (Coupling) 분석

### 4.1 결합도 종류와 본 프로젝트의 위치

강의 §6 결합도 5단계 (낮음 ← Data < Stamp < Control < Common < Content → 높음):

- **대부분의 Service ↔ Repository 호출은 Data Coupling**: `chatRepository.findById(...)` — 단순 식별자/엔티티만 주고받음. ✅
- **Stamp Coupling** 존재: `MessageStreamingService.streamMessage(TurnContext ctx)` 가 `TurnContext` 전체를 받지만 일부만 쓰는 코드 경로가 있음 (cancel 분기에서 `ctx.aiMessage().getId()` 만 필요). 큰 문제는 아님.
- **Event Coupling (Data Coupling 수준)**: `MessageCompletedEvent` → `UsageAccumulationListener`, `TurnSummaryListener`. 이벤트 페이로드(record)만 공유하므로 Data Coupling. ✅
- **Common Coupling 위험 1곳**: `StreamingRegistry.contexts (ConcurrentHashMap)` — 인스턴스 전역 mutable state. **다중 인스턴스 배포 시 cancel 신호가 전달 안 됨** (스케일 아웃 불가). 단, 전용 클래스로 격리하여 영향 범위를 최소화. ✅ (이전: MessageService 내 inline)
- **Content Coupling 없음** ✅

### 4.2 모듈 간 의존 그래프

| 의존 | 평가 |
|---|---|
| `MessageService` → `MessageStreamingService` | ✅ 같은 도메인 내 위임. SRP 분리 결과. |
| `MessageStreamingService` → `ChatService` | `touchAncestorChain` 호출. 같은 도메인 내 service-to-service. 허용 범위. |
| `MessageStreamingService` → `LlmStreamClient` (interface) | ✅ DIP 정상. 필요한 인터페이스만 의존 (ISP). |
| `TurnSummaryService` → `LlmSummaryClient`, `LlmBranchTitleClient` | ✅ DIP + ISP 정상. |
| `HealthController` → `LlmHealthClient` | ✅ ISP 정상. 이전: fat `AiServerClient` 전체 의존. |
| `MessageStreamingService` → `ApplicationEventPublisher` | ✅ Spring 제공 추상화. 이벤트 기반으로 cross-domain 결합 해소. |
| `UsageAccumulationListener` ← `MessageCompletedEvent` | ✅ 이벤트 기반. 이전: `MessageService` → `UsageService` 직접 호출. |
| `ChatService` → `MemberRepository` | 도메인 경계를 넘는 repo 접근. 작은 위반이지만 소유권 검증용으로 허용 범위. |

### 4.3 CBO / Ca / Ce 추정 (강의 §6 metric)

대표 클래스 기준:

| 클래스 | Ce (사용함) | Ca (사용됨) | 평가 |
|---|---|---|---|
| `MessageService` | 5 | 1 (Controller) | ✅ 이전 Ce≥10 → 5로 절반 이상 감소 |
| `MessageStreamingService` | 9 | 1 (MessageService) | 스트리밍 특성상 여러 인프라 의존은 불가피 |
| `ChatService` | 4 | 3 | 양호 |
| `ContextAssembler` | 4 | 1 | 양호 |
| `GraphService` | 5 | 1 | 양호. 내부 위임 3개(TurnTraverser, FrontierCalculator, GraphNodeMapper). |
| `TurnSummaryService` | 5 | 1 (Listener) | 양호 |
| `LlmStreamClient` (interface) | 0 | 2 | 안정 모듈 (I≈0). ✅ |
| `BaseEntity` | 0 | many | 안정. ✅ |

→ 이전 분석 대비 `MessageService` 의 Ce가 10+ → 5 로 크게 개선. **refactoring 대상에서 제외**.

---

## 5. 응집도 (Cohesion) 분석

강의 §6 응집도 7단계 (낮음 ← Coincidental < Logical < Temporal < Procedural < Communicational < Sequential < Functional → 높음):

| 모듈 | 응집 유형 | 근거 |
|---|---|---|
| `ChatService` | **Functional** ✅ | "대화 자체에 대한 CRUD + 소유권 검증" 한 가지 목적. |
| `MessageService` | **Functional** ✅ | Turn/Message 생성 + 분기 생성만. 283줄. 이전: 518줄 6가지 책임 → 분해 완료. |
| `MessageStreamingService` | **Functional** ✅ | "SSE 스트리밍 라이프사이클 관리" 단일 목적. |
| `StreamingRegistry` | **Functional** ✅ | "스트리밍 컨텍스트 등록/취소/제거" 단일 목적. 40줄. |
| `TurnSummaryService` | **Functional** ✅ | "비동기 요약/제목 생성" 단일 목적. |
| `ContextAssembler` | **Functional** ✅ | "LLM 호출 전 맥락 조립 + 압축" 단일 목적. |
| `GraphService` | **Functional** ✅ | "그래프 조회 + 확장" 오케스트레이션. 170줄. 이전: 369줄 → 분해 완료. |
| `TurnTraverser` | **Functional** ✅ | "turnSequence 기반 UP/DOWN 순회" 단일 목적. |
| `FrontierCalculator` | **Functional** ✅ | "그래프 경계점 계산" 단일 목적. |
| `GraphNodeMapper` | **Functional** ✅ | "Entity → DTO 변환" 단일 목적. |
| `ExplorerService` | **Functional** ✅ | "탐색기 페이지 조회 + 트리 구성" 단일 목적. |
| Repository 들 | **Functional** ✅ | 각 엔티티에 대한 영속화. |
| `ErrorStatus` | **Logical** | 모든 에러 코드를 하나의 enum 으로 묶음. 의도된 카탈로그(REP/CCP 측면 OK). |

> 이전 분석에서 가장 큰 문제로 지적된 `MessageService` 의 **Communicational+Procedural 혼합 응집**이 해소되어, 현재 모든 주요 서비스가 **Functional Cohesion** 을 달성.

---

## 6. SOLID 적용 현황

### 6.1 SRP — Single Responsibility Principle

| 클래스 | 평가 |
|---|---|
| `MessageService` | ✅ Turn/Message 생성 + 분기 생성만. 이전: 6가지 책임 → 분해 완료. |
| `MessageStreamingService` | ✅ SSE 라이프사이클 전담. |
| `StreamingRegistry` | ✅ 스트리밍 상태 관리 전담. |
| `TurnSummaryService` | ✅ 비동기 요약/제목 전담. |
| `ChatService` | ✅ |
| `GraphService` → `TurnTraverser` / `FrontierCalculator` / `GraphNodeMapper` | ✅ 각자 한 가지 역할. |
| `AiServerWebClient` | ✅ 외부 HTTP 호출 + 파싱. |
| `UsageAccumulationListener` | ✅ 이벤트 수신 → 사용량 누적만. |
| Entity `Chat` | ✅ 상태 변경 메서드가 자기 상태만 다룸. |

### 6.2 OCP — Open/Closed Principle

- ✅ `LlmModel` 추가 시 기존 코드 수정 없이 enum 항목만 늘리면 됨.
- ✅ `BaseErrorCode` interface — 새 도메인 에러 코드 enum 을 추가해도 `GlobalExceptionHandler` 는 그대로.
- ✅ **이벤트 시스템**: 새로운 후처리(예: 로깅, 알림)를 추가할 때 기존 `MessageStreamingService` 를 수정하지 않고 새 `@EventListener` 만 등록하면 됨. 이전 분석의 OCP 위반 지적 해소.
- ✅ **LLM 인터페이스**: 새 LLM 제공자 추가 시 `AiServerWebClient` 를 교체하거나 새 구현 추가. 호출자 변경 없음.

### 6.3 LSP — Liskov Substitution

- `AiServerWebClient` 가 `LlmStreamClient`, `LlmCompletionClient`, `LlmHealthClient`, `LlmSummaryClient`, `LlmBranchTitleClient` 5개 인터페이스를 모두 구현. 각 인터페이스 계약을 준수. ✅
- 테스트에서 `TestLlmStreamClient` 가 `LlmStreamClient` 를 구현하여 정상 대체 가능 확인. ✅

### 6.4 ISP — Interface Segregation

- ✅ **5개 세분 인터페이스 분리 완료** (이전: 5 메서드 fat `AiServerClient` 1개):
  - `LlmStreamClient` — `MessageStreamingService` 가 사용
  - `LlmHealthClient` — `HealthController` 가 사용
  - `LlmSummaryClient` — `TurnSummaryService` 가 사용
  - `LlmBranchTitleClient` — `TurnSummaryService` 가 사용
  - `LlmCompletionClient` — 동기 완성용 (확장 대비)
- 각 호출자는 자기가 필요한 인터페이스만 주입받음. 강의 §6 의 "client should not be forced to depend on interfaces it does not use" 정확히 준수.

### 6.5 DIP — Dependency Inversion

- ✅ `MessageStreamingService` → `LlmStreamClient` (interface) → `AiServerWebClient` (구현). 강의 슬라이드의 Car/Tire 예시와 동일 패턴.
- ✅ `TurnSummaryService` → `LlmSummaryClient` / `LlmBranchTitleClient` (interface) → `AiServerWebClient`.
- ✅ Spring 의 DI 자체가 강의에서 말한 "Framework 이 제어를 가져간다 (IoC)" 와 동일.
- ✅ `domain.llm` 패키지가 **도메인 계약(인터페이스) + 구현**을 모두 포함. 이전: `ai/`(구현)와 `domain.llm/`(미사용 추상화)로 분리되어 혼란 → 통합 완료.

---

## 7. 디자인 패턴 적용 현황

강의 §7 의 GoF 패턴을 본 프로젝트에서 추적:

| 패턴 | 적용 위치 |
|---|---|
| **Façade** | `ApiResponse<T>`, `GlobalExceptionHandler` — 내부 복잡성 숨김. |
| **Strategy** | `LlmStreamClient` 등 5개 인터페이스 — 다른 LLM 구현으로 교체 가능. 테스트에서 `TestLlmStreamClient` 로 교체 확인. |
| **Builder** | Lombok `@Builder` — 모든 엔티티/DTO에서 사용. |
| **Singleton** | Spring Bean (`@Service`, `@Component`) — 모든 서비스가 사실상 싱글톤. |
| **Adapter** | `AiServerWebClient` — 외부 AI 서버의 SSE 형식을 우리 도메인의 `Consumer<String> onChunk` 로 변환. |
| **Template Method (약)** | `BaseEntity` 의 createdAt/updatedAt 공통 처리. |
| **Iterator** | Spring Data 의 `Page<Chat>`, `Stream` — 자료구조에 종속되지 않는 순회. |
| **Observer** ✅ | `MessageCompletedEvent` 발행 → `UsageAccumulationListener`(사용량 누적) + `TurnSummaryListener`(요약/제목 생성) 가 `@TransactionalEventListener(AFTER_COMMIT)` 로 비동기 처리. 강의 Observer 슬라이드와 1:1 대응. **이전: 직접 호출 → 이벤트 기반으로 전환 완료.** |
| **State (잠재적, 미적용)** | `MessageStatus` enum (STREAMING/COMPLETED/CANCELED/FAILED) — 현재는 조건 분기로 처리. 상태 전이가 더 복잡해지면 State Pattern 후보. |

---

## 8. 디자인 원리 — Abstraction / Encapsulation / Information Hiding

### 8.1 Abstraction (강의 §6)
- `LlmModel` enum 의 `getContextWindow()` — 모델별 컨텍스트 윈도우 추상화. ✅
- DTO ↔ Entity 분리 — API 표현과 영속 표현의 분리. ✅
- `MessageCompletedEvent` record — 도메인 이벤트를 필요한 데이터(memberId, chatId, turnId, tokens, content)만 담은 추상화. ✅

### 8.2 Encapsulation / Information Hiding
- Entity 의 `@AllArgsConstructor(access = AccessLevel.PRIVATE)`, `@NoArgsConstructor(access = AccessLevel.PROTECTED)` — 외부에서 직접 생성 차단. ✅
- 상태 변경은 명시적 메서드(`updateTitle`, `softDelete`, `touchLastActivityAt`)로만. ✅
- `StreamingRegistry` 가 `ConcurrentHashMap` 을 내부에 캡슐화. 외부는 `register/find/cancel/remove` 메서드만 사용. ✅ (이전: `MessageService` 안에 `private Map` + record 직접 노출)
- `TurnContext` record 에 `memberId` 를 사전 추출하여 포함 — virtual thread 에서 lazy loading 으로 인한 불필요한 DB 접근 차단. ✅

### 8.3 Interface Separation
- Controller ↔ Service ↔ Repository 의 3계층이 인터페이스 또는 명시적 메서드 경계로 잘 분리. ✅
- Service 간 직접 호출이 최소화됨: `MessageStreamingService` → `ChatService.touchAncestorChain()` 1건 정도. ✅
- Cross-domain 의존은 이벤트 기반(`MessageCompletedEvent`)으로 해소. ✅

---

## 9. 패키지 설계 원칙 (REP / CCP / CRP)

강의 §6-II 의 3원칙:

| 원칙 | 본 프로젝트 |
|---|---|
| **REP** (Reuse/Release Equivalence) | 단일 jar 로 묶여 함께 릴리즈됨. domain 별로 분리 배포는 안 함 — 모놀리식이므로 자연스러움. |
| **CCP** (Common Closure — 같은 이유로 변하면 같이 묶기) | ✅ `ErrorStatus` enum, `BaseEntity`, `ApiResponse` 가 잘 만족. ✅ `LlmModel`/`LlmProvider` 가 `domain.llm.enums` 로 이동하여 LLM 변경 시 `domain.chat` 은 영향 없음. 이전 CCP 위반 해소. |
| **CRP** (Common Reuse — 같이 쓰는 것은 같이 묶기) | ✅ DTO 가 `*ReqDto`, `*ResDto` inner-record 로 그룹화되어 잘 묶임. ✅ `domain.llm.client/` 의 인터페이스 5개가 관련 DTO와 함께 같은 패키지에 위치. |

강의 §6-II 정리: **REP/CCP 는 "뭘 넣어야 하는가", CRP 는 "뭘 빼야 하는가"**. CCP 관점에서 이전에 `chat` 패키지가 LLM enum 까지 끌어안던 문제가 해소됨 (`@Deprecated` 호환 유지).

---

## 10. 품질 속성 (ISO 25010) 평가

| 품질 | 현황 |
|---|---|
| **Reliability** | SSE 실패 시 `MessageStatus.FAILED` 로 전이, partial content 보존, 이벤트 기반 후처리에 fallback 패턴 적용 (AI 실패 → substring 요약) — ✅ |
| **Performance Efficiency** | Virtual Thread 사용으로 동시 스트리밍 효율 ↑. N+1 쿼리 배치화 (`touchAncestorChain`, `buildAncestorChain`), 2-query 전략으로 HHH90003004 해소 ✅ |
| **Maintainability** | 모든 주요 서비스가 Functional Cohesion. 이벤트 기반 확장 용이. 인지 복잡도 크게 개선 ✅ |
| **Portability** | DB는 JPA 추상화로 교체 가능. AI 서버도 5개 인터페이스 분리로 교체 가능 ✅ |
| **Security** | JWT + Spring Security + BCrypt + STATELESS 세션 + CORS 화이트리스트. ✅ |
| **Compatibility** | REST + SSE 표준 사용. ✅ |
| **Usability (개발자)** | Swagger 통합 ✅ |

---

## 11. 리팩토링 이력 및 잔여 개선 후보

### 11.1 해소 완료 (이전 분석 대비)

| # | 항목 | 상태 | 해소 방법 |
|---|---|---|---|
| #1 | `MessageService` 분해 (SRP, Cohesion) | ✅ 완료 | `MessageStreamingService`, `StreamingRegistry`, `TurnSummaryService` 분리. 518줄→283줄. |
| #2 | `domain.llm` 모듈 정리 (DIP) | ✅ 완료 | `ai/` 패키지를 `domain/llm/` 으로 통합. 미사용 `LlmClient`/`MockLlmClient` 제거. |
| #3 | Usage/Summary 를 Observer 로 (결합↓) | ✅ 완료 | `MessageCompletedEvent` + `@TransactionalEventListener`. |
| #5 | `AiServerClient` ISP 분리 | ✅ 완료 | 5개 세분 인터페이스. |
| #6 | `LlmModel`/`LlmProvider` 위치 이동 (CCP) | ✅ 완료 | `domain.llm.enums` 로 이동. 기존 위치는 `@Deprecated`. |
| #7 | `GraphService` 분해 | ✅ 완료 | `TurnTraverser`, `FrontierCalculator`, `GraphNodeMapper` 분리. 369줄→170줄. |
| 추가 | N+1 쿼리 최적화 | ✅ 완료 | `touchAncestorChain`/`buildAncestorChain` 배치화, TurnRepository 2-query 전략, `TurnContext.memberId` 추가로 lazy loading 제거. |

### 11.2 잔여 개선 후보

#### Refactoring A — `StreamingRegistry` 의 분산 환경 대응 (이전 #4)
**문제**: 인스턴스 메모리에 저장 → 다중 인스턴스 시 cancel 신호 전달 불가.
**제안**:
- 단기: 그대로 두되 sticky session / 단일 인스턴스 명시.
- 장기: Redis Pub/Sub 로 cancel 시그널 브로드캐스트.

#### Refactoring B — Soft delete cascade 의 N+1 위험 (이전 #8)
`ChatService.softDeleteCascade` 가 재귀로 `findAllByParentId` 호출. 분기 깊이가 깊어지면 쿼리 폭증.
→ `findAllByRootChatId()` 로 1회 조회 후 메모리에서 트리 구성, 일괄 soft delete.

#### Refactoring C — 토큰 추정 (`estimateTokens`) 의 정확성 (이전 #10)
`text.length() / 4` 의 단순 추정. 한국어/영어 비율, 모델별 tokenizer 차이를 무시.
→ 모델별 `Tokenizer` Strategy 도입 (Strategy Pattern). 강의 §7 Strategy 예시와 일치.

#### Refactoring D — `@Deprecated` enum 제거
`domain.chat.enums.LlmModel`/`LlmProvider` 가 `@Deprecated` 상태로 남아 있음. 프론트엔드 등 모든 참조가 `domain.llm.enums` 로 전환되면 제거.

#### Refactoring E — Member 조회 중복 감소
SecurityContext 의 Member 를 서비스 레이어에 직접 전달하는 구조로 변경하면 `memberRepository.findById()` 중복 호출 감소. 단, 컨트롤러-서비스 인터페이스 전체를 건드려야 하므로 영향 범위 큼.

---

## 12. 잘 된 점 (Strength) — 그대로 유지할 것

- ✅ 도메인별 패키지 분할이 `package_by_domain.md` 명세와 일치 — Spec-driven.
- ✅ 5개 ISP 준수 인터페이스를 통한 외부 시스템 격리 — DIP + ISP 의 모범.
- ✅ JPA Entity 의 strict 캡슐화 (생성자 가시성 제한, 변경 메서드만 노출).
- ✅ `ApiResponse` + `ErrorStatus` 의 통합 응답/에러 카탈로그 — Façade + CCP.
- ✅ JWT + STATELESS + CORS 화이트리스트 — 보안 베이스라인 충족.
- ✅ Soft delete + cascade — 데이터 보존성 ↑.
- ✅ Virtual Thread 활용으로 SSE 동시성 효율 확보.
- ✅ **이벤트 기반 후처리** — Observer 패턴으로 cross-domain 결합 해소. 확장 용이.
- ✅ **서비스 분해** — 모든 주요 서비스가 Functional Cohesion 달성. 인지 복잡도 ↓.
- ✅ **쿼리 최적화** — N+1 배치화, 2-query 전략으로 HHH90003004 해소.
- ✅ **`@TransactionalEventListener(AFTER_COMMIT)`** — 트랜잭션 커밋 후에만 비동기 처리 실행, 데이터 정합성 보장.

---

## 13. 정리 — 강의 metric 기준 종합 평가

| 항목 | 이전 | 현재 | 핵심 근거 |
|---|---|---|---|
| 추상화 | A | A | LlmModel, DTO/Entity 분리, Repository 추상화, Event record |
| 모듈화 | B+ | **A** | 모든 서비스가 단일 책임. 비대 클래스 없음 |
| Information Hiding | A− | **A** | StreamingRegistry 캡슐화, TurnContext.memberId 사전 추출 |
| Interface Separation | B | **A** | 5개 ISP 준수 LLM 인터페이스 |
| Coupling | B | **A−** | 이벤트 기반 cross-domain 분리, Ce 절반 감소 |
| Cohesion | B− | **A** | 모든 주요 서비스 Functional Cohesion |
| SRP | B | **A** | MessageService 분해, GraphService 분해 |
| OCP | B+ | **A−** | 이벤트 시스템으로 확장 시 기존 코드 수정 불필요 |
| LSP | A | A | 위반 없음. 테스트에서 대체 구현 검증 |
| ISP | C+ | **A** | fat interface 1개 → 세분 인터페이스 5개 |
| DIP | A− | **A** | domain.llm 통합, 중복 추상화 해소 |
| 아키텍처 적합성 | A | A | Layered + Client-Server + Event-driven + SSE 적절 조합 |

**한 줄 요약**: 이전 분석에서 지적된 핵심 문제(MessageService 과집중, ISP 위반, Observer 미적용, LLM 모듈 혼란, GraphService 비대)가 모두 해소되었다. 현재 **모든 주요 서비스가 Functional Cohesion + SRP 를 달성**하고, **이벤트 기반 아키텍처로 cross-domain 결합이 최소화**되어 강의 metric 기준 전체 등급이 B+ → A− 수준으로 향상되었다.

---

## 참고: 강의 슬라이드와의 매핑 (자체 점검 체크리스트)

- [x] What vs How 단계 인식 (§1)
- [x] Subsystem = Package 매핑 (§6)
- [x] Module / Component / Allocation 관점 구분 (§6)
- [x] Design Goal & Style 결정 (Layered+Client-Server+Event-driven+SSE)
- [x] Quality Objective (ISO 25010 7항목) 점검
- [x] 결합도 5단계 진단
- [x] 응집도 7단계 진단
- [x] SOLID 5원칙 진단
- [x] REP / CCP / CRP 진단
- [x] 적용된 GoF 패턴 식별 (Observer 실적용 확인)
- [x] 미적용/잠재 패턴(State, Strategy-Tokenizer) 식별
- [x] Architecture Evaluation (SAAM/ATAM 관점의 시나리오 = "AI server 가 죽었을 때", "동시 스트리밍 수백건", "분기 깊이 증가 시")
- [x] Refactoring 이력 추적 (이전 분석 → 해소 완료 → 잔여 후보)
