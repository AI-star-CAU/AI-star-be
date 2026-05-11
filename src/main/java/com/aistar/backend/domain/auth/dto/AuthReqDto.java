package com.aistar.backend.domain.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class AuthReqDto {

    public record SignUp(
            @NotBlank @Email @Size(max = 100)
            String email,

            @NotBlank @Size(min = 8, max = 255)
            String password,

            @NotBlank @Size(max = 20)
            String name
    ) {}

    public record Login(
            @NotBlank @Email
            String email,

            @NotBlank
            String password
    ) {}
}
