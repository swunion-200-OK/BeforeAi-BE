package site.siyoung.beforeaibe.dto.request;

import java.time.Instant;
import java.util.UUID;

// 진행 상황 트리 전체 (API 명세서 2.6). version은 SSE 이벤트 id와 같음
public record ProgressResponse(
        UUID requestId,
        long version,
        Instant updatedAt,
        ProgressNodeResponse root
) {
}
