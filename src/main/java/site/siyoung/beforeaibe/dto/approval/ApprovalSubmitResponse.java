package site.siyoung.beforeaibe.dto.approval;

import site.siyoung.beforeaibe.entity.enums.RequestStatus;

import java.util.List;
import java.util.UUID;

// 승인 서명 제출 결과 (3.6 CORE-05).
// executionRequested=false면 서명은 보관됐지만 웹쉘 실행 요청이 아직 안 됐다는 뜻(연결 실패 후 재시도).
// executionRequested=true여도 status가 VERIFICATION_FAILED면 웹쉘이 승인 서명 검증을 거부해 실행 세션이 열리지 않은 것
public record ApprovalSubmitResponse(
        UUID requestId,
        RequestStatus status,
        List<UUID> approvedTaskIds,
        List<UUID> remainingTaskIds,
        boolean executionRequested
) {
}
