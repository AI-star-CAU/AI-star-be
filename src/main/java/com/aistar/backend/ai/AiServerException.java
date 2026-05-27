package com.aistar.backend.ai;

import java.util.Set;

public class AiServerException extends RuntimeException {

    private static final Set<Integer> UNAVAILABLE_STATUS_CODES = Set.of(401, 403, 429, 500, 502, 503, 504);

    private final Integer statusCode;

    public AiServerException(String message) {
        this(message, null, null);
    }

    public AiServerException(String message, Throwable cause) {
        this(message, null, cause);
    }

    public AiServerException(String message, Integer statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public static AiServerException fromStatus(String message, int statusCode, String body) {
        String responseBody = body == null || body.isBlank() ? "empty response body" : body;
        return new AiServerException(message + " (status=" + statusCode + "): " + responseBody, statusCode, null);
    }

    public Integer statusCode() {
        return statusCode;
    }

    public boolean isLikelyUnavailable() {
        if (statusCode != null && UNAVAILABLE_STATUS_CODES.contains(statusCode)) {
            return true;
        }
        String message = getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("returned html")
                || lower.contains("timed out")
                || lower.contains("connection refused")
                || lower.contains("connection reset")
                || lower.contains("unknown host")
                || lower.contains("failed to resolve");
    }
}
