package site.siyoung.beforeaibe.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// API 명세서 1.2 오류 코드 표
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "요청 값이 잘못되었습니다"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "로그인이 필요하거나 토큰이 만료되었습니다"),
    OTP_REQUIRED(HttpStatus.UNAUTHORIZED, "OTP 확인이 필요합니다"),
    FORBIDDEN_ROLE(HttpStatus.FORBIDDEN, "권한이 없습니다"),
    INTERNAL_NETWORK_ONLY(HttpStatus.FORBIDDEN, "내부망에서만 가능한 기능입니다"),
    NOT_APPROVER(HttpStatus.FORBIDDEN, "이 작업의 승인권자가 아닙니다"),
    KEY_NOT_REGISTERED(HttpStatus.FORBIDDEN, "승인자 공개키가 웹쉘 정책에 등록되지 않았습니다"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "대상을 찾을 수 없습니다"),
    INVALID_STATE(HttpStatus.CONFLICT, "현재 상태에서 할 수 없는 동작입니다"),
    PLAN_HASH_MISMATCH(HttpStatus.CONFLICT, "서명하려는 해시가 현재 Plan 해시와 다릅니다"),
    EDIT_CONFLICT(HttpStatus.CONFLICT, "에디터를 연 뒤 저장소가 바뀌었습니다"),
    OWNER_MINIMUM(HttpStatus.CONFLICT, "담당자를 2명 미만으로 줄일 수 없습니다"),
    RETRY_LIMIT(HttpStatus.CONFLICT, "분석 재시도 횟수를 넘었습니다"),
    FORBIDDEN_TASK(HttpStatus.UNPROCESSABLE_CONTENT, "금지 작업이 포함되어 있습니다"),
    EXPORT_TOO_LARGE(HttpStatus.UNPROCESSABLE_CONTENT, "내보낼 감사 로그가 10만 건을 넘습니다"),
    WEBSHELL_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "웹쉘에 연결할 수 없습니다"),
    // 명세 1.2 표에는 없지만, 예상하지 못한 서버 오류도 같은 형식으로 응답하기 위해 둠
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다");

    private final HttpStatus status;
    private final String message;
}
