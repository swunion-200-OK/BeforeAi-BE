package site.siyoung.beforeaibe.dto.request;

import site.siyoung.beforeaibe.entity.enums.RequestStatus;

import java.util.List;
import java.util.UUID;

// 제출 응답 (API 명세서 4.3). selfApprovableTaskIds는 요청자 본인이 승인할 수 있는 작업
public record SubmitResponse(
        UUID id,
        RequestStatus status,
        UUID planId,
        String planHash,
        List<UUID> selfApprovableTaskIds
) {
}
