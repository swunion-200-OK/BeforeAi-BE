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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import site.siyoung.beforeaibe.entity.value.RequiredApprover;
import site.siyoung.beforeaibe.entity.value.TaskContent;
import site.siyoung.beforeaibe.enums.Risk;
import site.siyoung.beforeaibe.enums.TaskStatus;
import site.siyoung.beforeaibe.enums.TaskType;

import java.util.List;
import java.util.UUID;

@Getter
@Entity
@Table(
        name = "tasks",
        uniqueConstraints = @UniqueConstraint(name = "uk_tasks_plan_order", columnNames = {"plan_id", "task_order"})
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    // order는 SQL 예약어라 컬럼명만 task_order로 둠
    @Column(name = "task_order", nullable = false)
    private int order;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Risk risk;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, length = 1024)
    private String target;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private TaskContent content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<UUID> relatedDepartments;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<RequiredApprover> requiredApprovers;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskStatus status;

    @Builder
    private Task(int order, TaskType type, Risk risk, String title, String target, TaskContent content,
                 List<UUID> relatedDepartments, List<RequiredApprover> requiredApprovers, TaskStatus status) {
        this.order = order;
        this.type = type;
        this.risk = risk;
        this.title = title;
        this.target = target;
        this.content = content;
        this.relatedDepartments = relatedDepartments;
        this.requiredApprovers = requiredApprovers;
        this.status = status;
    }

    void assignPlan(Plan plan) {
        this.plan = plan;
    }
}
