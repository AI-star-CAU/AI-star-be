# Phase 4 Refactoring Execution Report

작성일: 2026-05-28

## 1. 목표

`phase4_refactoring_plan.md` 기준으로, 기존 API/SSE 계약을 유지하면서 채팅 도메인 리팩토링을 단계적으로 적용했다.

## 2. 수행 범위

- P1-1~P1-3: `MessageService` 분해
  - `StreamingRegistry` 추출
  - `TurnSummaryService` 추출
  - `MessageStreamingService` 추출
- P4: usage/summary 후처리 이벤트화
- P5: LLM 클라이언트 인터페이스 분리
- P6: `GraphService` 책임 분해
- P7: `LlmModel`, `LlmProvider` enum 위치 정리 (`domain.llm.enums`) 및 하위 호환 유지

## 3. 주요 변경 사항

### 3.1 Message 흐름

- `MessageService`는 Turn/Message 생성, regenerate/edit/cancel 중심으로 책임 축소
- SSE 스트리밍, chunk/done/error 전송, 완료/실패 후처리는 `MessageStreamingService`로 위임
- 스트리밍 상태 저장/취소 플래그 처리는 `StreamingRegistry`로 분리
- summary 생성은 `TurnSummaryService`로 분리

### 3.2 후처리 이벤트화

- `MessageCompletedEvent` 도입
- `UsageAccumulationListener`에서 usage 누적
- `TurnSummaryListener`에서 summary 생성 트리거
- 트랜잭션 이벤트 리스너는 `AFTER_COMMIT` 기반으로 동작하도록 구성

### 3.3 LLM 인터페이스 분리

- `AiServerClient`를 다음 세분 인터페이스로 분리
  - `LlmStreamClient`
  - `LlmCompletionClient`
  - `LlmHealthClient`
  - `LlmSummaryClient`
  - `LlmBranchTitleClient`
- `HealthController`는 `LlmHealthClient`에만 의존하도록 변경

### 3.4 Graph 분해

- 탐색/프론티어/매핑을 분리
  - `TurnTraverser`
  - `FrontierCalculator`
  - `GraphNodeMapper`
- 기존 `GraphService`의 공개 API는 유지하고 내부 위임만 변경

### 3.5 Enum 위치 정리

- 실사용 enum은 `domain.llm.enums`로 이동
- 기존 `domain.chat.enums` enum은 `@Deprecated` 호환 타입으로 유지하여 비파괴 원칙 준수

## 4. 테스트 및 검증 결과

- `.\gradlew.bat compileJava`: 성공
- `.\gradlew.bat test --tests com.aistar.backend.MessageStreamingRegressionTest`: 성공
- `.\gradlew.bat test` (전체): 성공

추가 회귀 테스트:

- `MessageStreamingRegressionTest`
  - SSE 성공 경로 이벤트 순서/필드 검증
  - SSE 에러 경로 이벤트 계약 검증
  - usage 리스너 단일 누적 검증

## 5. 비파괴 원칙 준수 사항

- Controller 경로 변경 없음
- `MessageService` 공개 메서드 시그니처 유지
- SSE 이벤트 이름(`turn_started`, `chunk`, `turn_completed`, `error`, `cancelled`, `done`) 유지
- 기존 enum 참조 경로 호환성 유지(Deprecated 타입 제공)

## 6. 남은 확인 권장 사항

- Swagger 기반 수동 시나리오 재검증
  - 메시지 송신/완료
  - cancel
  - regenerate/edit
  - AI 서버 장애 시 에러 이벤트
- 운영 환경 기준 성능 지표(응답 시간, 에러율, 리소스) 기준선 대비 비교

## 7. 추가 보완 사항 (2026-05-29)

- 배경:
  - 스트리밍 완료 직후 후처리(`MessageCompletedEvent`) 구간에서 예외가 발생하면,
    이미 완료된 메시지가 `FAILED`로 덮어써질 수 있는 경계 리스크를 확인했다.
  - 동일 경로에서 usage 이벤트가 중복 발행될 가능성도 함께 존재했다.

- 수정 내용:
  - `MessageStreamingService`의 실패 처리 분기에서
    `message.getStatus() == MessageStatus.STREAMING`인 경우에만 `FAILED` 전환하도록 가드 추가
  - 동일 조건에서만 실패 경로 usage 이벤트를 발행하도록 제한

- 기대 효과:
  - 완료된 메시지 상태 오염 방지
  - 예외 경계에서의 usage 중복 누적 가능성 축소

- 검증:
  - `.\gradlew.bat test --tests com.aistar.backend.Phase2ApiTest --tests com.aistar.backend.Phase3ApiTest`: 성공
  - `.\gradlew.bat test --tests com.aistar.backend.Phase4ApiTest --tests com.aistar.backend.MessageStreamingRegressionTest`: 성공
  - `.\gradlew.bat test` (전체): 성공
