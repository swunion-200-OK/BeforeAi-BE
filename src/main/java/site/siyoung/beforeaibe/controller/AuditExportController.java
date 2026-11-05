package site.siyoung.beforeaibe.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import site.siyoung.beforeaibe.dto.audit.AuditExportCreateRequest;
import site.siyoung.beforeaibe.dto.audit.AuditExportFile;
import site.siyoung.beforeaibe.dto.audit.AuditExportResponse;
import site.siyoung.beforeaibe.dto.common.CursorPageResponse;
import site.siyoung.beforeaibe.security.AuthUser;
import site.siyoung.beforeaibe.security.InternalNetworkOnly;
import site.siyoung.beforeaibe.security.NetworkResolver;
import site.siyoung.beforeaibe.service.AuditExportService;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

// 감사 로그 내보내기 (API 명세서 3.9, CORE-13). 모두 내부망 전용
@RestController
@RequestMapping("/api/v1/audit-logs/exports")
@RequiredArgsConstructor
@InternalNetworkOnly
public class AuditExportController {

    private final AuditExportService auditExportService;
    private final NetworkResolver networkResolver;

    // 파일은 비동기로 만들므로 202로 받고, 상태 조회로 READY를 확인함
    @PostMapping
    public ResponseEntity<AuditExportResponse> start(@AuthenticationPrincipal AuthUser authUser,
                                                     @Valid @RequestBody AuditExportCreateRequest request,
                                                     HttpServletRequest httpRequest) {
        return ResponseEntity.accepted().body(auditExportService.start(authUser.id(), request,
                networkResolver.resolve(httpRequest), networkResolver.clientIp(httpRequest)));
    }

    // 목록 API 공통 페이지 규칙: ?cursor=&limit= (기본 20, 최대 100)
    @GetMapping
    public CursorPageResponse<AuditExportResponse> exports(
            @AuthenticationPrincipal AuthUser authUser,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return auditExportService.list(authUser.id(), cursor, limit);
    }

    @GetMapping("/{exportId}")
    public AuditExportResponse export(@AuthenticationPrincipal AuthUser authUser, @PathVariable UUID exportId) {
        return auditExportService.get(authUser.id(), exportId);
    }

    @GetMapping("/{exportId}/file")
    public ResponseEntity<byte[]> file(@AuthenticationPrincipal AuthUser authUser, @PathVariable UUID exportId) {
        AuditExportFile file = auditExportService.download(authUser.id(), exportId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }
}
