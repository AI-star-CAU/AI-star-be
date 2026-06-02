# Phase 4 Refactoring Plan

## 목표

`docs/design_principles_analysis.md`의 진단을 기준으로, 기존 API 동작을 유지하면서 백엔드의 책임 분리와 유지보수성을 개선한다.

이번 리팩토링의 원칙은 다음과 같다.

- 기존 Controller 경로와 응답 구조를 유지한다.
- SSE 이벤트 이름과 응답 순서를 최대한 유지한다.
- DB 스키마 변경은 별도 이슈로 분리한다.
- 한 번에 큰 구조 변경을 하지 않고, 컴파일 가능한 작은 단위로 나눈다.
- 각 단계마다 `compileJava`와 Swagger 기반 흐름 검증을 진행한다.
- 리팩토링 단계에서는 기존 공개 계약(Controller API, SSE 이벤트 계약, Service 공개 메서드)을 제거하지 않는다.
- 불가피한 구조 이동이 필요하면 기존 진입점을 유지한 채 내부 위임으로 전환하고, 삭제는 최종 안정화 이후 별도 단계에서 진행한다.

## 현재 진단 요약

분석 문서 기준으로 가장 큰 리팩토링 대상은 `MessageService`다.

현재 `MessageService`는 다음 책임을 동시에 가진다.

- Turn/Message 생성과 소유권 검증
- SSE emitter 생성과 스트리밍 응답 전송
- `streamingContexts` 기반 취소 상태 관리
- regenerate/edit/branch 흐름 처리
- AI 서버 호출 오케스트레이션
- usage 누적
- turn summary 비동기 생성

이 구조는 SRP, Cohesion, OCP 측면에서 리팩토링 필요성이 크다. 반면 외부 AI 서버 연동을 `AiServerClient` 인터페이스로 감싼 구조는 DIP 측면에서 좋은 방향이다.

## 우선순위

| 우선순위 | 대상 | 목적 | 위험도 |
| --- | --- | --- | --- |
| P1 | `MessageService` 분해 | 가장 큰 책임 집중 해소 | 중간 |
| P2 | `StreamingRegistry` 추출 | 취소 상태 관리 분리 | 낮음 |
| P3 | `TurnSummaryService` 추출 | summary 생성 책임 분리 | 낮음 |
| P4 | usage/summary 이벤트화 | cross-domain 직접 결합 완화 | 중간 |
| P5 | `AiServerClient` 인터페이스 분리 | ISP 개선 | 낮음 |
| P6 | `GraphService` 분해 | 그래프 탐색 책임 세분화 | 중간 |
| P7 | enum 위치 정리 | `LlmModel`, `LlmProvider`의 도메인 소속 명확화 | 중간 |

실행 순서는 우선순위와 별도로 다음처럼 고정한다.

1. P1-1: `StreamingRegistry` 추출
2. P1-2: `TurnSummaryService` 추출
3. P1-3: `MessageStreamingService` 추출
4. P4: 후처리 이벤트화
5. P5: `AiServerClient` 인터페이스 분리
6. P6: `GraphService` 분해
7. P7: enum 위치 정리

## 단계별 계획

### 1. 기준선 검증

리팩토링 전 현재 브랜치에서 최소 기준선을 확인한다.

```powershell
.\gradlew.bat compileJava
.\gradlew.bat bootRun
```

Swagger에서 다음 흐름을 확인한다.

- 회원가입 또는 로그인
- 채팅 생성
- `/api/v1/chats/{chatId}/messages` SSE 호출
- AI 서버가 꺼져 있을 때 앱 시작 자체가 실패하지 않는지 확인

자동화 검증도 함께 추가한다.

- SSE 이벤트 회귀 테스트(이벤트 순서, 필수 이벤트 존재, payload 필드)를 통합 테스트로 고정한다.
- 수동 Swagger 검증은 자동화 테스트의 보완 용도로만 사용한다.

### 2. `StreamingRegistry` 추출

현재 `MessageService` 내부의 `streamingContexts`를 별도 컴포넌트로 이동한다.

예상 구조:

```text
domain/chat/service/
  StreamingRegistry.java
```

역할:

- AI message id별 스트리밍 상태 저장
- cancel 요청 처리
- 스트리밍 종료 시 상태 제거

기대 효과:

- mutable shared state가 한 곳으로 격리된다.
- 이후 Redis나 Pub/Sub 기반으로 바꿀 여지가 생긴다.
- `MessageService`의 필드와 책임이 줄어든다.

### 3. `TurnSummaryService` 추출

`MessageService`의 summary 생성 로직을 별도 서비스로 이동한다.

예상 구조:

```text
domain/chat/service/
  TurnSummaryService.java
```

역할:

- AI 응답 완료 후 turn summary 생성 요청
- summary 저장
- 실패 시 메시지 송신 흐름에는 영향 주지 않기

기대 효과:

- 메시지 송신 흐름과 후처리 흐름이 분리된다.
- summary 실패가 스트리밍 핵심 로직을 더럽히지 않는다.

### 4. `MessageStreamingService` 추출

SSE emitter 생성, AI 서버 스트림 구독, chunk/done/error 이벤트 전송을 별도 서비스로 분리한다.

예상 구조:

```text
domain/chat/service/
  MessageStreamingService.java
```

역할:

- `SseEmitter` 생성
- AI 서버 스트림 호출
- SSE 이벤트 전송
- 스트림 완료/취소/실패 처리

`MessageService`는 Turn/Message 생성과 regenerate/edit/branch 준비만 담당하고, 실제 스트리밍은 `MessageStreamingService`에 위임한다.

### 5. 후처리 이벤트화

AI 응답 완료 후 직접 `UsageService`를 호출하는 구조를 Spring event 기반으로 바꾼다.

예상 구조:

```text
domain/chat/event/
  MessageCompletedEvent.java

domain/usage/listener/
  UsageAccumulationListener.java

domain/chat/listener/
  TurnSummaryListener.java
```

주의점:

- usage 중복 누적이 발생하지 않도록 이벤트 발행 위치를 하나로 고정한다.
- 트랜잭션 커밋 전/후 실행 시점을 명확히 한다.
- SSE 완료 이벤트와 DB 저장 완료 순서가 꼬이지 않게 한다.

이벤트 처리 시점 기본 규칙:

- 기본값은 `@TransactionalEventListener(phase = AFTER_COMMIT)`으로 고정한다.
- 커밋 실패 시 usage/summary 후처리가 실행되지 않아야 한다.
- 즉시 실행이 꼭 필요한 경우에만 예외로 두고, 해당 리스너에 이유를 주석으로 남긴다.

### 6. `AiServerClient` 인터페이스 분리

현재 `AiServerClient`는 stream, completion, health, summary, branch-title을 모두 포함한다. 호출자별 필요한 메서드만 주입받을 수 있도록 인터페이스를 나눈다.

예상 구조:

```text
domain/llm/client/
  LlmStreamClient.java
  LlmCompletionClient.java
  LlmHealthClient.java
  LlmSummaryClient.java
  LlmBranchTitleClient.java
```

`AiServerWebClient`는 여러 인터페이스를 구현할 수 있다.

기대 효과:

- Message 관련 서비스는 stream/complete에만 의존한다.
- HealthController는 health check에만 의존한다.
- ISP 위반이 줄어든다.

### 7. `GraphService` 분해

그래프 탐색 로직을 작은 역할 단위로 분리한다.

예상 구조:

```text
domain/chat/service/graph/
  TurnTraverser.java
  FrontierCalculator.java
  GraphNodeMapper.java
```

역할:

- `TurnTraverser`: UP/DOWN 방향 탐색
- `FrontierCalculator`: 더 펼칠 수 있는 노드 계산
- `GraphNodeMapper`: 엔티티를 응답 DTO로 변환

### 8. enum 위치 정리

`LlmModel`, `LlmProvider`가 채팅 도메인보다 LLM 도메인에 더 가깝다면 `domain.llm.enums`로 이동한다.

주의점:

- JPA enum 저장값이 바뀌면 안 된다.
- import 변경 범위가 넓으므로 마지막 단계에서 진행한다.

## 첫 작업 추천

가장 먼저 할 작업은 `StreamingRegistry` 추출이다.

이유:

- 기능 변경이 거의 없다.
- `MessageService`의 전역 mutable state를 먼저 분리할 수 있다.
- 이후 `MessageStreamingService` 추출의 발판이 된다.

두 번째 작업은 `TurnSummaryService` 추출이 좋다. 실패해도 핵심 송신 흐름에 영향이 적고, 리팩토링 효과가 바로 보인다.

## P1-1 작업용 체크리스트 (`StreamingRegistry` 추출)

비파괴 리팩토링 원칙:

- 기존 `MessageService`의 공개 메서드 시그니처는 유지한다.
- 기존 SSE 이벤트 이름/순서/payload 계약은 변경하지 않는다.
- 기능 교체 전에는 기존 코드 경로를 즉시 삭제하지 않고, 위임 전환 후 동일 동작을 검증한다.

작업 항목:

1. `StreamingRegistry` 클래스 생성 (`register/find/cancel/remove` 최소 메서드 정의)
2. `MessageService` 내부 `streamingContexts` 저장/조회/삭제 로직을 `StreamingRegistry` 호출로 치환
3. `cancelMessage`, `regenerateMessage`, `streamMessage` 경로에서 동일한 취소 플래그 동작 유지
4. `MessageService`의 기존 공개 메서드와 반환 타입 변경 없이 컴파일 통과
5. SSE 핵심 흐름 수동 검증 (`turn_started` → `chunk` → `turn_completed|error|cancelled`)
6. 취소 API 호출 시 스트리밍 중단과 상태 반영(메시지 상태/부분 content 저장) 확인
7. 변경 전/후 Swagger 호출 결과(HTTP 코드, 응답 필드) 차이 없는지 확인

완료 기준:

- `compileJava` 통과
- 자동화 SSE 회귀 테스트 통과
- 수동 검증 체크리스트 통과
- 기존 계약 삭제/변경 없음

## 검증 체크리스트

각 단계마다 다음을 확인한다.

```powershell
.\gradlew.bat compileJava
```

가능하면 다음 흐름도 Swagger에서 확인한다.

- 로그인 후 accessToken 발급
- 채팅 생성
- 메시지 송신 SSE 200 응답
- chunk 이벤트 수신
- turn_completed 이벤트 수신
- AI 서버 장애 시 SSE error 이벤트 또는 명확한 실패 응답 확인
- 취소 API 호출 시 스트리밍 중단 확인
- regenerate/edit 흐름이 기존과 동일하게 동작하는지 확인

자동화 테스트 항목도 함께 확인한다.

- SSE 이벤트 순서: `turn_started` → `chunk`(0..n) → `turn_completed|error|cancelled`
- SSE 이벤트 payload 필수 필드 존재 여부
- usage 누적이 단 1회만 발생하는지 검증
- 변경 전/후 API 응답 필드 및 SSE 이벤트 계약 diff 없음 확인

## 리스크

- SSE 이벤트 순서가 바뀌면 프론트가 깨질 수 있다.
- usage 누적을 이벤트화할 때 중복 누적이 발생할 수 있다.
- summary 생성 시점이 바뀌면 그래프/컨텍스트 조회 결과가 달라질 수 있다.
- 트랜잭션 경계가 바뀌면 메시지 저장과 스트리밍 완료 상태가 불일치할 수 있다.
- `streamingContexts`는 현재 단일 서버 기준 구조이므로, 분산 서버 대응은 이번 리팩토링의 구현 범위 밖으로 두고 문서화만 한다.

## 최종 성공 기준 (Go/No-Go)

최종 판정은 아래 3개 축을 모두 통과할 때만 `Go`로 본다. 하나라도 미달이면 `No-Go`로 보고 릴리즈/병합을 보류한다.

1. 기능 동일성
- 기존 API 응답 필드/타입/HTTP 코드 변경 없음
- SSE 이벤트 이름/순서/payload 계약 변경 없음
- 변경 전/후 계약 diff 결과 0건

2. 안정성
- SSE 정상 흐름(`turn_started` → `chunk` → `turn_completed`) 회귀 테스트 통과
- SSE 예외 흐름(`error`, `cancelled`) 회귀 테스트 통과
- usage 중복 누적 0건, 누락 0건

3. 운영 품질
- 기준선 대비 응답 시간 악화 없음(또는 팀이 합의한 허용 범위 이내)
- 기준선 대비 에러율 악화 없음
- 기준선 대비 리소스 사용량(CPU/메모리/스레드) 악화 없음

권장 절차:

1. 리팩토링 시작 전 기준선 측정값(응답 시간/에러율/리소스)을 기록한다.
2. 단계별 완료 시 동일 시나리오로 재측정한다.
3. 최종 단계 종료 후 Go/No-Go 체크리스트를 한 번 더 전체 수행한다.
