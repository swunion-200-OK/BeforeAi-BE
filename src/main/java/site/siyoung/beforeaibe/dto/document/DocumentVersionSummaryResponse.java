package site.siyoung.beforeaibe.dto.document;

import site.siyoung.beforeaibe.entity.DocumentVersion;

import java.time.Instant;
import java.util.UUID;

// 문서 버전 목록 항목 (3.7 GET /documents/{documentId}/versions). 본문은 버전 상세에서 조회
public record DocumentVersionSummaryResponse(
        UUID documentId,
        int version,
        UUID requestId,
        Instant createdAt
) {

    public static DocumentVersionSummaryResponse from(DocumentVersion version) {
        return new DocumentVersionSummaryResponse(version.getDocument().getId(), version.getVersion(),
                version.getRequest().getId(), version.getCreatedAt());
    }
}
