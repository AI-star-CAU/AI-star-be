package com.aistar.backend.global.apiPayload.code;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorStatus implements BaseErrorCode {

    // Common
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "COMMON_400", "잘못된 요청입니다."),
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "COMMON_4001", "입력값이 올바르지 않습니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "COMMON_403", "접근 권한이 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON_404", "리소스를 찾을 수 없습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON_500", "서버 오류가 발생했습니다."),

    // Auth
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_4011", "토큰이 유효하지 않습니다."),
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "AUTH_4012", "이메일 또는 비밀번호가 일치하지 않습니다."),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "AUTH_4013", "토큰이 만료되었습니다."),

    // Member
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "MEMBER_4041", "존재하지 않는 회원입니다."),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "MEMBER_4091", "이미 존재하는 이메일입니다."),

    // Chat
    CHAT_NOT_FOUND(HttpStatus.NOT_FOUND, "CHAT_4041", "존재하지 않는 대화입니다."),

    // Message
    MESSAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "MESSAGE_4041", "존재하지 않는 메시지입니다."),
    MESSAGE_CANCEL_NOT_ALLOWED(HttpStatus.CONFLICT, "MESSAGE_4091", "취소할 수 없는 상태의 메시지입니다."),
    MESSAGE_ACTION_NOT_ALLOWED(HttpStatus.CONFLICT, "MESSAGE_4092", "수정/재생성할 수 없는 상태의 메시지입니다."),

    // Branch
    BRANCH_INVALID(HttpStatus.BAD_REQUEST, "BRANCH_4001", "분기를 생성할 수 없는 turn입니다."),
    BRANCH_NOT_FOUND(HttpStatus.NOT_FOUND, "BRANCH_4041", "존재하지 않는 분기입니다."),
    BRANCH_ALREADY_DELETED(HttpStatus.CONFLICT, "BRANCH_4091", "이미 삭제된 분기입니다."),

    // Turn
    TURN_NOT_FOUND(HttpStatus.NOT_FOUND, "TURN_4041", "존재하지 않는 turn입니다."),

    // Graph
    GRAPH_INVALID_PARAM(HttpStatus.BAD_REQUEST, "GRAPH_4001", "유효하지 않은 그래프 조회 파라미터입니다."),

    // Explorer
    EXPLORER_INVALID_PARAM(HttpStatus.BAD_REQUEST, "EXPLORER_4001", "유효하지 않은 탐색기 조회 파라미터입니다."),

    // Usage
    USAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "USAGE_4041", "사용량 기록을 찾을 수 없습니다."),

    // LLM
    AI_SERVER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "LLM_5031", "ai server가 점검중입니다."),
    LLM_CALL_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "LLM_5001", "LLM 서비스 호출에 실패했습니다."),
    ;

    private final HttpStatus status;
    private final String code;
    private final String message;
}
