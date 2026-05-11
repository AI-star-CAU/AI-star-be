package com.aistar.backend.domain.auth.controller;

import com.aistar.backend.domain.auth.dto.AuthReqDto;
import com.aistar.backend.domain.auth.dto.AuthResDto;
import com.aistar.backend.domain.auth.service.AuthService;
import com.aistar.backend.global.apiPayload.ApiResponse;
import com.aistar.backend.global.apiPayload.code.SuccessStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Auth", description = "인증 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "회원가입")
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AuthResDto.SignUp> signUp(@RequestBody @Valid AuthReqDto.SignUp dto) {
        return ApiResponse.onSuccess(SuccessStatus.CREATED, authService.signUp(dto));
    }

    @Operation(summary = "로그인")
    @PostMapping("/login")
    public ApiResponse<AuthResDto.Login> login(@RequestBody @Valid AuthReqDto.Login dto) {
        return ApiResponse.onSuccess(SuccessStatus.OK, authService.login(dto));
    }
}
