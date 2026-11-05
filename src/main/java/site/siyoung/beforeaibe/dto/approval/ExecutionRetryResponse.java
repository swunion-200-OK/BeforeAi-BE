package site.siyoung.beforeaibe.dto.approval;

import site.siyoung.beforeaibe.entity.enums.RequestStatus;

import java.util.UUID;

// 실행 재시도 결과. 웹쉘이 검증을 거부하면 status=VERIFICATION_FAILED, executionRequested=false
public record ExecutionRetryResponse(UUID requestId, RequestStatus status, boolean executionRequested) {
}
