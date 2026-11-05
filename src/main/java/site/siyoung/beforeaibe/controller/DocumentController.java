package site.siyoung.beforeaibe.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import site.siyoung.beforeaibe.dto.common.CursorPageResponse;
import site.siyoung.beforeaibe.dto.document.DocumentResponse;
import site.siyoung.beforeaibe.dto.document.DocumentVersionResponse;
import site.siyoung.beforeaibe.dto.document.DocumentVersionSummaryResponse;
import site.siyoung.beforeaibe.service.DocumentService;

import java.util.UUID;

// 프로젝트 문서·문서 버전 조회 (API 명세서 3.7). 권한·망 제한 없이 로그인 사용자가 조회
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    // 목록 API 공통 페이지 규칙: ?cursor=&limit= (기본 20, 최대 100)
    @GetMapping("/projects/{projectId}/documents")
    public CursorPageResponse<DocumentResponse> documents(
            @PathVariable UUID projectId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return documentService.list(projectId, cursor, limit);
    }

    @GetMapping("/documents/{documentId}/versions")
    public CursorPageResponse<DocumentVersionSummaryResponse> versions(
            @PathVariable UUID documentId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return documentService.versions(documentId, cursor, limit);
    }

    // 조회만 함. 이 버전으로 되돌리려면 문서 롤백(POST /requests/{requestId}/rollback)으로 승인받아야 함
    @GetMapping("/documents/{documentId}/versions/{version}")
    public DocumentVersionResponse version(@PathVariable UUID documentId, @PathVariable int version) {
        return documentService.version(documentId, version);
    }
}
