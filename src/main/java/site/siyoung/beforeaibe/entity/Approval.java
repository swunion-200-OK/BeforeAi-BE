package site.siyoung.beforeaibe.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import site.siyoung.beforeaibe.entity.value.ApprovalEnvelope;
import site.siyoung.beforeaibe.enums.Network;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// 웹 서비스가 보관하는 승인 기록. planHash·taskIds·keyId는 화면 표시·사전 검사용 평문이고 실행 판단에는 쓰지 않음
@Getter
@Entity
@Table(name = "approvals")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Approval {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false)
    private Request request;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    @Column(nullable = false)
    private UUID approverId;

    @Column(nullable = false)
    private UUID keyId;

    @Column(nullable = false, length = 128)
    private String planHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<UUID> taskIds;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Network network;

    @Embedded
    private ApprovalEnvelope envelope;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Builder
    private Approval(Request request, Plan plan, UUID approverId, UUID keyId, String planHash,
                     List<UUID> taskIds, Network network, ApprovalEnvelope envelope) {
        this.request = request;
        this.plan = plan;
        this.approverId = approverId;
        this.keyId = keyId;
        this.planHash = planHash;
        this.taskIds = taskIds;
        this.network = network;
        this.envelope = envelope;
    }
}
