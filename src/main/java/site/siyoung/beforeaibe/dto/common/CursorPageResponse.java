package site.siyoung.beforeaibe.dto.common;

import java.util.List;

// API 명세서 1.1 목록 응답 형식
public record CursorPageResponse<T>(List<T> items, String nextCursor) {
}
