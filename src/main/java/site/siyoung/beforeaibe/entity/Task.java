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
import site.siyoung.beforeaibe.entity.value.TaskExplanation;
import site.siyoung.beforeaibe.entity.enums.Risk;
import site.siyoung.beforeaibe.entity.enums.TaskStatus;
import site.siyoung.beforeaibe.entity.enums.TaskType;

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

    // AI 작업 설명(표시 전용). Plan 해시 대상이 아니고, AI가 만들지 않은 작업은 null
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private TaskExplanation explanation;

    @Builder
    private Task(int order, TaskType type, Risk risk, String title, String target, TaskContent content,
                 List<UUID> relatedDepartments, List<RequiredApprover> requiredApprovers, TaskStatus status,
                 TaskExplanation explanation) {
        this.order = order;
        this.type = type;
        this.risk = risk;
        this.title = title;
        this.target = target;
        this.content = content;
        this.relatedDepartments = relatedDepartments;
        this.requiredApprovers = requiredApprovers;
        this.status = status;
        this.explanation = explanation;
    }

    // 새 Plan(삭제 반영·실패 작업 재시도)에 넣을 같은 내용의 작업. order는 원래 값을 유지하고 내용이 같으므로 설명도 유지함
    public Task copy(TaskStatus status) {
        return Task.builder()
                .order(order)
                .type(type)
                .risk(risk)
                .title(title)
                .target(target)
                .content(content)
                .relatedDepartments(relatedDepartments)
                .requiredApprovers(requiredApprovers)
                .status(status)
                .explanation(explanation)
                .build();
    }

    public void changeStatus(TaskStatus status) {
        this.status = status;
    }

    void assignPlan(Plan plan) {
        this.plan = plan;
    }
}
