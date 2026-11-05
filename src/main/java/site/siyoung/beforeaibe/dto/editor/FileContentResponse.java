package site.siyoung.beforeaibe.dto.editor;

import site.siyoung.beforeaibe.webshell.WebshellClient;

// GET /projects/{projectId}/files/content 응답: 파일 경로·기준·커밋·내용. 계약 미확정 (API 명세서 3.8, CORE-05)
public record FileContentResponse(String path, String ref, String baseCommit, String content) {

    public static FileContentResponse from(WebshellClient.FileContentResult result) {
        return new FileContentResponse(result.path(), result.ref(), result.baseCommit(), result.content());
    }
}
