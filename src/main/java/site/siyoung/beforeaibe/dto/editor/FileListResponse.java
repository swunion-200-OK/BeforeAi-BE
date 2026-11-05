package site.siyoung.beforeaibe.dto.editor;

import site.siyoung.beforeaibe.webshell.WebshellClient;

import java.util.List;

// GET /projects/{projectId}/files 응답: 기준 커밋 + 디렉터리 항목. 계약 미확정 (API 명세서 3.8, CORE-05)
public record FileListResponse(String baseCommit, String path, List<FileEntry> items) {

    public static FileListResponse from(WebshellClient.FileListResult result) {
        List<FileEntry> items = result.items().stream()
                .map(item -> new FileEntry(item.name(), item.path(), FileType.valueOf(item.type())))
                .toList();
        return new FileListResponse(result.baseCommit(), result.path(), items);
    }
}
