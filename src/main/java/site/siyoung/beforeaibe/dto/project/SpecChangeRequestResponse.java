package site.siyoung.beforeaibe.dto.project;

import com.fasterxml.jackson.annotation.JsonInclude;
import site.siyoung.beforeaibe.entity.SpecChangeRequest;
import site.siyoung.beforeaibe.entity.enums.SpecChangeRequestStatus;

import java.time.Instant;
import java.util.UUID;

// rejectionReason은 반려된 요청에만 있음
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SpecChangeRequestResponse(
        UUID id,
        UUID projectId,
        SpecChangeRequestStatus status,
        String content,
        String reason,
        UUID requesterId,
        Instant createdAt,
        String rejectionReason
) {

    public static SpecChangeRequestResponse from(SpecChangeRequest changeRequest) {
        return new SpecChangeRequestResponse(changeRequest.getId(), changeRequest.getProject().getId(),
                changeRequest.getStatus(), changeRequest.getContent(), changeRequest.getReason(),
                changeRequest.getRequester().getId(), changeRequest.getCreatedAt(),
                changeRequest.getRejectionReason());
    }
}
