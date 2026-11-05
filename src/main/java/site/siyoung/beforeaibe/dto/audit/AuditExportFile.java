package site.siyoung.beforeaibe.dto.audit;

import site.siyoung.beforeaibe.entity.AuditExport;
import site.siyoung.beforeaibe.entity.enums.ExportFormat;

// 내려받을 감사 로그 파일. 컨트롤러가 Content-Type·Content-Disposition 헤더를 붙여 바이너리로 응답함
public record AuditExportFile(String fileName, String contentType, byte[] content) {

    private static final String CSV_CONTENT_TYPE = "text/csv; charset=utf-8";
    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    public static AuditExportFile from(AuditExport export) {
        String contentType = export.getFormat() == ExportFormat.CSV ? CSV_CONTENT_TYPE : XLSX_CONTENT_TYPE;
        return new AuditExportFile(export.getFileName(), contentType, export.getFileContent());
    }
}
