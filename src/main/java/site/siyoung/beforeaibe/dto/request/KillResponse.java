package site.siyoung.beforeaibe.dto.request;

import site.siyoung.beforeaibe.entity.enums.RequestStatus;

import java.util.List;
import java.util.UUID;

// skippedTaskIds: 중단으로 실행되지 않은 작업 (API 명세서 4.6)
public record KillResponse(UUID id, RequestStatus status, List<UUID> skippedTaskIds) {
}
