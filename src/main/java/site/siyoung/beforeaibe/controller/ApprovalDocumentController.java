package site.siyoung.beforeaibe.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.siyoung.beforeaibe.dto.approvaldocument.ApprovalDocumentVersionSummaryResponse;
import site.siyoung.beforeaibe.dto.common.CursorPageResponse;
import site.siyoung.beforeaibe.security.AuthUser;
import site.siyoung.beforeaibe.service.ApprovalDocumentService;

import java.util.UUID;

// 결재 문서 미리보기·PDF (전체 망). 미리보기도 서버가 만든 PDF를 inline으로, 다운로드는 attachment로 내려 줌.
// produces를 걸지 않아 Accept와 상관없이 오류는 명세 형식 JSON으로 응답함(GlobalExceptionHandler가 Content-Type 고정)
@RestController
@RequestMapping("/api/v1/requests/{requestId}/approval-document")
@RequiredArgsConstructor
public class ApprovalDocumentController {

    private final ApprovalDocumentService approvalDocumentService;

    // 최신 판 미리보기 (승인 진행 중에도 조회 가능)
    @GetMapping
    public ResponseEntity<byte[]> preview(@AuthenticationPrincipal AuthUser authUser, @PathVariable UUID requestId) {
        return pdf(approvalDocumentService.preview(authUser.id(), requestId), "inline",
                "approval-document-" + requestId + ".pdf");
    }

    @GetMapping("/versions")
    public CursorPageResponse<ApprovalDocumentVersionSummaryResponse> versions(
            @AuthenticationPrincipal AuthUser authUser, @PathVariable UUID requestId) {
        return approvalDocumentService.versions(authUser.id(), requestId);
    }

    // 특정 판 미리보기. 이전 판은 기록으로 보기만 하고 다운로드(/pdf)는 최신 판만 됨
    @GetMapping("/versions/{version}")
    public ResponseEntity<byte[]> previewVersion(@AuthenticationPrincipal AuthUser authUser,
                                                 @PathVariable UUID requestId, @PathVariable int version) {
        return pdf(approvalDocumentService.previewVersion(authUser.id(), requestId, version), "inline",
                "approval-document-" + requestId + "-v" + version + ".pdf");
    }

    // 필요한 승인이 모두 끝난 최신 판 PDF 다운로드. 실행 뒤에 받으면 작업별 최종 상태가 함께 담김
    @GetMapping("/pdf")
    public ResponseEntity<byte[]> download(@AuthenticationPrincipal AuthUser authUser, @PathVariable UUID requestId) {
        return pdf(approvalDocumentService.download(authUser.id(), requestId), "attachment",
                "approval-document-" + requestId + ".pdf");
    }

    private ResponseEntity<byte[]> pdf(byte[] content, String disposition, String fileName) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.builder(disposition).filename(fileName).build().toString())
                .body(content);
    }
}
