package site.siyoung.beforeaibe.dto.approvaldocument;

import site.siyoung.beforeaibe.entity.ApprovalDocumentVersion;

import java.time.Instant;
import java.util.UUID;

// 결재 문서 판 목록 항목 (GET /requests/{requestId}/approval-document/versions). 내용은 판 미리보기(PDF)로 조회
public record ApprovalDocumentVersionSummaryResponse(
        UUID documentId,
        int version,
        UUID planId,
        String planHash,
        ApprovalDocumentResponse.Status status,
        boolean downloadable,
        Instant createdAt
) {

    public static ApprovalDocumentVersionSummaryResponse of(ApprovalDocumentVersion version,
                                                            ApprovalDocumentResponse.Status status) {
        return new ApprovalDocumentVersionSummaryResponse(version.getDocument().getId(), version.getVersion(),
                version.getPlan().getId(), version.getPlan().getPlanHash(), status,
                status == ApprovalDocumentResponse.Status.APPROVED, version.getCreatedAt());
    }
}
