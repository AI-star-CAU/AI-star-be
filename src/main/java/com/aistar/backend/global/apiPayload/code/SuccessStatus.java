package com.aistar.backend.global.apiPayload.code;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum SuccessStatus implements BaseSuccessCode {

    OK(HttpStatus.OK, "COMMON_200", "요청에 성공하였습니다."),
    CREATED(HttpStatus.CREATED, "COMMON_201", "리소스가 생성되었습니다."),
    ;

    private final HttpStatus status;
    private final String code;
    private final String message;
}
