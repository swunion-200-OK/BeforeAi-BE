package site.siyoung.beforeaibe.dto.approval;

import site.siyoung.beforeaibe.entity.enums.RequestStatus;

import java.util.UUID;

public record RejectionResponse(UUID requestId, RequestStatus status) {
}
