## 패키지 구조 지침

도메인별 패키지 구조를 사용한다.

최상위 구조:

- global
- domain.auth
- domain.member
- domain.chat
- domain.llm

auth는 회원가입/로그인 유스케이스를 담당한다.  
member는 Member 엔티티와 내 정보 조회/회원 탈퇴를 담당한다.  
chat은 Chat, Turn, Message를 함께 포함한다. Turn과 Message는 Chat 없이 독립적으로 의미가 약하므로 별도 도메인으로 분리하지 않는다.  
llm은 외부 LLM 호출을 담당하며, chat 도메인은 구체 LLM API에 직접 의존하지 않고 LlmClient 인터페이스에 의존한다.  
global은 ApiResponse, 예외 처리, Security, Auditing 등 공통 인프라만 둔다.

Phase 2에서는 billing, subscription, payment, social, branch, graph 도메인은 구현하지 않는다.

예시
```
src/main/java/com/ait
├─ AitApplication.java
│
├─ global
│   ├─ api
│   │   └─ ApiResponse.java
│   │
│   ├─ error
│   │   ├─ BaseErrorCode.java
│   │   ├─ BaseSuccessCode.java
│   │   ├─ ErrorStatus.java
│   │   ├─ SuccessStatus.java
│   │   ├─ GeneralException.java
│   │   └─ GlobalExceptionHandler.java
│   │
│   ├─ security
│   │   ├─ SecurityConfig.java
│   │   ├─ JwtAuthenticationFilter.java
│   │   ├─ JwtTokenProvider.java
│   │   ├─ CustomUserDetails.java
│   │   └─ CustomUserDetailsService.java
│   │
│   ├─ config
│   │   ├─ JpaAuditingConfig.java
│   │   └─ CorsConfig.java
│   │
│   └─ entity
│       └─ BaseTimeEntity.java
│
└─ domain
├─ auth
│   ├─ controller
│   │   └─ AuthController.java
│   ├─ service
│   │   └─ AuthService.java
│   ├─ dto
│   │   ├─ AuthReqDto.java
│   │   └─ AuthResDto.java
│   └─ converter
│       └─ AuthConverter.java
│
├─ member
│   ├─ controller
│   │   └─ MemberController.java
│   ├─ service
│   │   └─ MemberService.java
│   ├─ entity
│   │   └─ Member.java
│   ├─ repository
│   │   └─ MemberRepository.java
│   ├─ dto
│   │   ├─ MemberReqDto.java
│   │   └─ MemberResDto.java
│   ├─ converter
│   │   └─ MemberConverter.java
│   └─ enums
│       └─ MemberType.java
│
├─ chat
│   ├─ controller
│   │   ├─ ChatController.java
│   │   └─ MessageController.java
│   ├─ service
│   │   ├─ ChatCommandService.java
│   │   ├─ ChatQueryService.java
│   │   ├─ TurnQueryService.java
│   │   └─ MessageCommandService.java
│   ├─ entity
│   │   ├─ Chat.java
│   │   ├─ Turn.java
│   │   └─ Message.java
│   ├─ repository
│   │   ├─ ChatRepository.java
│   │   ├─ TurnRepository.java
│   │   └─ MessageRepository.java
│   ├─ dto
│   │   ├─ ChatReqDto.java
│   │   ├─ ChatResDto.java
│   │   ├─ TurnResDto.java
│   │   ├─ MessageReqDto.java
│   │   └─ MessageResDto.java
│   ├─ converter
│   │   ├─ ChatConverter.java
│   │   ├─ TurnConverter.java
│   │   └─ MessageConverter.java
│   └─ enums
│       ├─ SenderType.java
│       ├─ MessageStatus.java
│       ├─ LlmProvider.java
│       └─ LlmModel.java
│
└─ llm
├─ client
│   ├─ LlmClient.java
│   ├─ OpenAiLlmClient.java
│   ├─ GoogleLlmClient.java
│   └─ AnthropicLlmClient.java
├─ dto
│   ├─ LlmRequest.java
│   ├─ LlmChunk.java
│   └─ LlmResponse.java
└─ service
└─ LlmRouter.java
```