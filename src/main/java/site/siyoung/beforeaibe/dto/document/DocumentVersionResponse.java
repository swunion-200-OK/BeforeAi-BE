package site.siyoung.beforeaibe.dto.document;

import site.siyoung.beforeaibe.entity.DocumentVersion;

import java.time.Instant;
import java.util.UUID;

// 특정 문서 버전 내용 (3.7 GET /documents/{documentId}/versions/{version})
public record DocumentVersionResponse(
        UUID documentId,
        int version,
        String body,
        Instant createdAt
) {

    public static DocumentVersionResponse from(DocumentVersion version) {
        return new DocumentVersionResponse(version.getDocument().getId(), version.getVersion(), version.getBody(),
                version.getCreatedAt());
    }
}
