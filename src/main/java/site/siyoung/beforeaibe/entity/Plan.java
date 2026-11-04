package site.siyoung.beforeaibe.entity;

import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import site.siyoung.beforeaibe.enums.PlanKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Getter
@Entity
@Table(name = "plans")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Plan {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false)
    private Request request;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PlanKind kind;

    @Column(nullable = false, length = 512)
    private String repository;

    @Column(nullable = false, length = 64)
    private String baseCommit;

    // 제출(submit) 시 실행기가 발급하므로 그 전까지는 null
    @Column(length = 128)
    private String planHash;

    @Column(columnDefinition = "text")
    private String executorSignature;

    @OrderBy("order ASC")
    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Task> tasks = new ArrayList<>();

    @Builder
    private Plan(Request request, PlanKind kind, String repository, String baseCommit,
                 String planHash, String executorSignature) {
        this.request = request;
        this.kind = kind;
        this.repository = repository;
        this.baseCommit = baseCommit;
        this.planHash = planHash;
        this.executorSignature = executorSignature;
    }

    // Task 추가는 addTask()로만 하도록 읽기 전용으로 노출
    public List<Task> getTasks() {
        return Collections.unmodifiableList(tasks);
    }

    public void addTask(Task task) {
        tasks.add(task);
        task.assignPlan(this);
    }
}
