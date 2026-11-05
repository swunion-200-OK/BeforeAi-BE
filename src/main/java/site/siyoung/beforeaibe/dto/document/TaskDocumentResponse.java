package site.siyoung.beforeaibe.dto.document;

import java.util.UUID;

// AI가 완성한 문서와 기존 문서의 diff (3.7 GET /requests/{requestId}/tasks/{taskId}/document).
// 아직 없는 문서를 새로 만드는 작업이면 documentId는 null
public record TaskDocumentResponse(
        UUID documentId,
        UUID taskId,
        String body,
        String diff
) {
}
