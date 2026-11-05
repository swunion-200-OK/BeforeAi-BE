package site.siyoung.beforeaibe.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import site.siyoung.beforeaibe.entity.enums.ExportFormat;
import site.siyoung.beforeaibe.entity.enums.ExportStatus;
import site.siyoung.beforeaibe.entity.value.AuditExportFilters;

import java.time.Instant;
import java.util.UUID;

// 감사 로그 내보내기 작업 (CORE-13). 요청한 본인만 내려받고, 준비된 뒤 1시간이 지나면 EXPIRED
@Getter
@Entity
@Table(name = "audit_exports")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditExport extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requester_id", nullable = false)
    private User requester;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ExportFormat format;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ExportStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private AuditExportFilters filters;

    @Column(nullable = false)
    private int rowCount;

    private String fileName;

    private Instant expiresAt;

    // 만든 파일 내용. READY일 때만 있고, 만료되면 지움
    @Column(columnDefinition = "bytea")
    private byte[] fileContent;

    @Builder
    private AuditExport(User requester, ExportFormat format, ExportStatus status, AuditExportFilters filters,
                        int rowCount) {
        this.requester = requester;
        this.format = format;
        this.status = status;
        this.filters = filters;
        this.rowCount = rowCount;
    }

    public void markReady(String fileName, Instant expiresAt) {
        this.status = ExportStatus.READY;
        this.fileName = fileName;
        this.expiresAt = expiresAt;
    }

    public void markReady(String fileName, byte[] fileContent, Instant expiresAt) {
        markReady(fileName, expiresAt);
        this.fileContent = fileContent;
    }

    public void markFailed() {
        this.status = ExportStatus.FAILED;
    }

    // 만료되면 파일을 지움 (준비 후 1시간 뒤 삭제)
    public void expire() {
        this.status = ExportStatus.EXPIRED;
        this.fileContent = null;
    }

    public boolean isExpiredAt(Instant now) {
        return status == ExportStatus.READY && expiresAt != null && !now.isBefore(expiresAt);
    }
}
