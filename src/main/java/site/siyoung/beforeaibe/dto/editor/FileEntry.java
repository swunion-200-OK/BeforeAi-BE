package site.siyoung.beforeaibe.dto.editor;

// 디렉터리 목록의 한 항목 (GET /projects/{projectId}/files). 계약 미확정 — 컬렉션 예시 기준
public record FileEntry(String name, String path, FileType type) {
}
