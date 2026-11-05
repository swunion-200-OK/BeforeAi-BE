package site.siyoung.beforeaibe.dto.document;

import site.siyoung.beforeaibe.entity.Document;

import java.time.Instant;
import java.util.UUID;

// 프로젝트 문서 목록 항목 (3.7 GET /projects/{projectId}/documents). 필드는 컬렉션 예시를 따름
public record DocumentResponse(
        UUID id,
        UUID projectId,
        String title,
        String path,
        int currentVersion,
        Instant updatedAt
) {

    public static DocumentResponse from(Document document) {
        return new DocumentResponse(document.getId(), document.getProject().getId(), document.getTitle(),
                document.getPath(), document.getCurrentVersion(), document.getUpdatedAt());
    }
}
