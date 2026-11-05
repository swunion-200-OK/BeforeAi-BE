package site.siyoung.beforeaibe.exception;

import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import site.siyoung.beforeaibe.dto.common.ErrorResponse;

import java.util.LinkedHashMap;
import java.util.Map;

// 모든 오류를 명세 1.2 형식 { "error": { code, message, details } }로 응답
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException e) {
        return error(e.getErrorCode(), e.getMessage(), e.getDetails());
    }

    // 요청 본문 @Valid 실패: 어느 필드가 잘못됐는지 details에 담음
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        Map<String, Object> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(error -> fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return validationError(Map.of("fields", fields));
    }

    // 쿼리 파라미터(@Min, @Max 등) 검증 실패
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleHandlerMethodValidation(HandlerMethodValidationException e) {
        Map<String, Object> fields = new LinkedHashMap<>();
        e.getParameterValidationResults().forEach(result -> fields.putIfAbsent(
                result.getMethodParameter().getParameterName(),
                result.getResolvableErrors().getFirst().getDefaultMessage()));
        return validationError(Map.of("fields", fields));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> handleUnreadable(Exception e) {
        return validationError(Map.of());
    }

    // 서비스의 사전 검사를 동시 요청이 통과해 unique·FK 제약에 걸린 경우. 중복·형식 오류와 같이 400으로 응답
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        log.warn("데이터 제약 위반: {}", e.getMostSpecificCause().getMessage());
        return error(ErrorCode.VALIDATION_ERROR, "다른 데이터와 충돌하는 요청입니다", Map.of());
    }

    // 그 밖의 예외. Spring MVC 예외(없는 경로, 지원하지 않는 메서드·미디어 타입, 필수 파라미터 누락 등)는
    // 상태 코드로 명세 코드에 매핑하고, 나머지는 로그를 남기고 500으로 응답
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {
        // SSE·PDF를 보내던 중 클라이언트가 끊으면 응답을 더 쓸 수 없음. 오류가 아니므로 쓰기를 시도하지 않음
        if (e instanceof AsyncRequestNotUsableException || isCommitted()) {
            log.debug("이미 보낸 응답에서 난 예외라 오류 응답을 쓰지 않음: {}", e.toString());
            return null;
        }
        if (e instanceof org.springframework.web.ErrorResponse springError) {
            HttpStatusCode status = springError.getStatusCode();
            if (status.value() == 404) {
                return error(ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.getMessage(), Map.of());
            }
            if (status.is4xxClientError()) {
                return validationError(Map.of());
            }
        }
        log.error("처리하지 못한 예외", e);
        return error(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getMessage(), Map.of());
    }

    private ResponseEntity<ErrorResponse> validationError(Map<String, Object> details) {
        return error(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.getMessage(), details);
    }

    // Content-Type을 JSON으로 고정해 Accept 협상을 거치지 않게 함. 그래야 SSE 구독(Accept: text/event-stream)에서
    // 권한 오류가 나도 500(HttpMediaTypeNotAcceptableException)이 아니라 명세 형식의 오류로 응답함
    // 다만 Spring은 서블릿 응답에 이미 지정된 Content-Type을 우선하므로, SSE·PDF 처리 중 난 예외는 여기서 먼저 바꿔 줌
    private ResponseEntity<ErrorResponse> error(ErrorCode code, String message, Map<String, Object> details) {
        if (isCommitted()) {
            return null;
        }
        HttpServletResponse response = currentResponse();
        if (response != null) {
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        }
        return ResponseEntity.status(code.getStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponse.of(code, message, details));
    }

    private boolean isCommitted() {
        HttpServletResponse response = currentResponse();
        return response != null && response.isCommitted();
    }

    private HttpServletResponse currentResponse() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getResponse() : null;
    }
}
