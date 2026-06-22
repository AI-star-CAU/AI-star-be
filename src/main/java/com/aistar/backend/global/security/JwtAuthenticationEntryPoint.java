package com.aistar.backend.global.security;

import com.aistar.backend.global.apiPayload.ApiResponse;
import com.aistar.backend.global.apiPayload.code.ErrorStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {

        ErrorStatus errorStatus = (ErrorStatus) request.getAttribute("jwtError");
        if (errorStatus == null) {
            errorStatus = ErrorStatus.INVALID_TOKEN;
        }

        response.setStatus(errorStatus.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        ApiResponse<Void> body = ApiResponse.onFailure(errorStatus, null);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
