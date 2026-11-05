package site.siyoung.beforeaibe.dto.audit;

import site.siyoung.beforeaibe.entity.AuditExport;
import site.siyoung.beforeaibe.entity.enums.ExportFormat;
import site.siyoung.beforeaibe.entity.enums.ExportStatus;

import java.time.Instant;
import java.util.UUID;

// 감사 로그 내보내기 상태 (3.9). fileName·expiresAt·downloadUrl은 READY일 때만 채움
// (PROCESSING·FAILED에서의 존재 여부는 명세에 없어 내려받을 수 있을 때만 줌)
public record AuditExportResponse(
        UUID exportId,
        ExportStatus status,
        ExportFormat format,
        int rowCount,
        String fileName,
        Instant expiresAt,
        String downloadUrl
) {

    public static AuditExportResponse from(AuditExport export) {
        boolean ready = export.getStatus() == ExportStatus.READY;
        return new AuditExportResponse(export.getId(), export.getStatus(), export.getFormat(), export.getRowCount(),
                ready ? export.getFileName() : null,
                ready ? export.getExpiresAt() : null,
                ready ? "/api/v1/audit-logs/exports/" + export.getId() + "/file" : null);
    }
}
