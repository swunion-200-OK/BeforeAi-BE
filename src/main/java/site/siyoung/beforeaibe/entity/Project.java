package site.siyoung.beforeaibe.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

// 프로젝트마다 담당 부서·저장소·담당자(2명 이상)가 정해짐 (ADM-08). 담당자 2명 이상 규칙은 서비스에서 검사
@Getter
@Entity
@Table(name = "projects")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Project extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "department_id", nullable = false)
    private Department department;

    @Column(nullable = false, length = 512)
    private String repository;

    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ProjectOwner> owners = new ArrayList<>();

    @Builder
    private Project(String name, Department department, String repository) {
        this.name = name;
        this.department = department;
        this.repository = repository;
    }

    // 담당자 변경은 addOwner()/removeOwner()로만 하도록 읽기 전용으로 노출
    public List<ProjectOwner> getOwners() {
        return Collections.unmodifiableList(owners);
    }

    public void addOwner(User user) {
        owners.add(ProjectOwner.builder().project(this).user(user).build());
    }

    public void removeOwner(User user) {
        owners.removeIf(owner -> owner.getUser().getId().equals(user.getId()));
    }
}
