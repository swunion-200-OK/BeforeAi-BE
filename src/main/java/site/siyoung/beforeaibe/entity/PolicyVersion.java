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
import site.siyoung.beforeaibe.entity.enums.PolicyPreset;
import site.siyoung.beforeaibe.entity.value.ApprovalMatrixRule;

import java.util.List;
import java.util.Map;
import java.util.UUID;

// 관리자 서명을 붙여 웹쉘에 배포한 정책 묶음의 버전. 프리셋·승인 기준표·금지 규칙·공개키가 바뀔 때마다 새 버전이 생기고
// 가장 큰 version이 현재 정책. policyHash는 웹쉘 GET /v1/policies/current와 정합성을 비교하는 데 씀
@Getter
@Entity
@Table(name = "policy_versions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PolicyVersion extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PolicyPreset preset;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<ApprovalMatrixRule> approvalMatrix;

    @Column(nullable = false, length = 128)
    private String policyHash;

    // 이전 정책은 null. 새 정책은 재전송할 정확한 전체 payload를 보존함.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> policyPayload;

    // 관리자 정책 서명(adminProof). 웹 서비스는 전달만 하고 검증은 웹쉘이 함
    @Column(nullable = false)
    private UUID adminKeyId;

    @Column(nullable = false, columnDefinition = "text")
    private String adminSignature;

    // 웹쉘에 연결하지 못했으면 false로 남고 다시 배포함
    @Column(nullable = false)
    private boolean webshellApplied;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @Builder
    private PolicyVersion(int version, PolicyPreset preset, List<ApprovalMatrixRule> approvalMatrix, String policyHash,
                          UUID adminKeyId, String adminSignature, boolean webshellApplied, User createdBy) {
        this.version = version;
        this.preset = preset;
        this.approvalMatrix = approvalMatrix;
        this.policyHash = policyHash;
        this.adminKeyId = adminKeyId;
        this.adminSignature = adminSignature;
        this.webshellApplied = webshellApplied;
        this.createdBy = createdBy;
    }

    public void markWebshellApplied() {
        this.webshellApplied = true;
    }

    public void attachPayload(Map<String, Object> payload) {
        if (policyPayload != null) {
            throw new IllegalStateException("정책 스냅샷은 변경할 수 없습니다");
        }
        this.policyPayload = payload;
    }
}
