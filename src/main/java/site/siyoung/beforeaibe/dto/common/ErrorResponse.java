package site.siyoung.beforeaibe.dto.common;

import site.siyoung.beforeaibe.exception.ErrorCode;

import java.util.Map;

// API 명세서 1.2 공통 오류 본문: { "error": { code, message, details } }
public record ErrorResponse(Error error) {

    public record Error(String code, String message, Map<String, Object> details) {
    }

    public static ErrorResponse of(ErrorCode errorCode) {
        return of(errorCode, errorCode.getMessage(), Map.of());
    }

    public static ErrorResponse of(ErrorCode errorCode, String message, Map<String, Object> details) {
        return new ErrorResponse(new Error(errorCode.name(), message, details));
    }
}
