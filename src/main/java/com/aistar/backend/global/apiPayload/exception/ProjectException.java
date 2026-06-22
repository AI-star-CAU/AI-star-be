package com.aistar.backend.global.apiPayload.exception;

import com.aistar.backend.global.apiPayload.code.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class ProjectException extends RuntimeException {

    private final BaseErrorCode errorCode;
}
