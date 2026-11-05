package site.siyoung.beforeaibe.dto.request;

import site.siyoung.beforeaibe.entity.enums.RequestStatus;

import java.util.UUID;

// 실패 작업 재시도로 만든 새 Plan과 해시. 이전 승인 서명은 쓸 수 없음
public record PlanRetryResponse(
        UUID id,
        RequestStatus status,
        UUID planId,
        String planHash
) {
}
