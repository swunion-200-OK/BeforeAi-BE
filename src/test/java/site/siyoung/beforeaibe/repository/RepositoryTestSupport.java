package site.siyoung.beforeaibe.repository;

import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import site.siyoung.beforeaibe.entity.Department;
import site.siyoung.beforeaibe.entity.Plan;
import site.siyoung.beforeaibe.entity.Project;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.SigningKey;
import site.siyoung.beforeaibe.entity.Task;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.value.RequiredApprover;
import site.siyoung.beforeaibe.entity.value.TaskContent;
import site.siyoung.beforeaibe.entity.enums.ApproverRule;
import site.siyoung.beforeaibe.entity.enums.PlanKind;
import site.siyoung.beforeaibe.entity.enums.RequestSource;
import site.siyoung.beforeaibe.entity.enums.RequestStatus;
import site.siyoung.beforeaibe.entity.enums.Risk;
import site.siyoung.beforeaibe.entity.enums.Role;
import site.siyoung.beforeaibe.entity.enums.SigningKeyStatus;
import site.siyoung.beforeaibe.entity.enums.TaskStatus;
import site.siyoung.beforeaibe.entity.enums.TaskType;

import java.util.List;
import java.util.UUID;

final class RepositoryTestSupport {

    // Task.relatedDepartments(jsonb)에 넣는 부서 ID. jsonb라 FK 대상이 아님
    static final UUID BACKEND_DEPT_ID = UUID.randomUUID();

    private RepositoryTestSupport() {
    }

    // requests는 projects·users FK가 있으므로 DB에 저장할 요청은 newRequest()/persistRequest()로 만듦
    static Request request(UUID projectId, UUID requesterId) {
        return Request.builder()
                .projectId(projectId)
                .requesterId(requesterId)
                .prompt("로그인 실패 시 에러 메시지를 바꾸고 스테이징에 배포해줘")
                .source(RequestSource.PROMPT)
                .status(RequestStatus.REVIEWING)
                .analysisAttempts(1)
                .build();
    }

    // 부서·요청자·프로젝트를 먼저 저장하고, 그 ID를 가리키는 (아직 저장하지 않은) 요청을 만듦
    static Request newRequest(TestEntityManager em) {
        Department department = em.persist(department("개발팀"));
        User requester = em.persist(user("REQ-" + UUID.randomUUID().toString().substring(0, 8), department));
        Project project = em.persist(project(department));
        return request(project.getId(), requester.getId());
    }

    static Request persistRequest(TestEntityManager em) {
        return em.persist(newRequest(em));
    }

    static Department department(String name) {
        return Department.builder().name(name).build();
    }

    static Project project(Department department) {
        return Project.builder()
                .name("인증 서비스")
                .department(department)
                .repository("git@internal:auth-service.git")
                .build();
    }

    static User user(String employeeNumber) {
        return user(employeeNumber, null);
    }

    static User user(String employeeNumber, Department department) {
        return User.builder()
                .employeeNumber(employeeNumber)
                .passwordHash("{bcrypt}hash")
                .name("홍길동")
                .role(Role.EMPLOYEE)
                .departmentId(department == null ? null : department.getId())
                .active(true)
                .passwordChangeRequired(true)
                .build();
    }

    static SigningKey signingKey(User user, String publicKey, SigningKeyStatus status) {
        return SigningKey.builder()
                .user(user)
                .algorithm("Ed25519")
                .publicKey(publicKey)
                .deviceName("업무용 노트북")
                .status(status)
                .build();
    }

    static Plan plan(Request request) {
        return Plan.builder()
                .request(request)
                .kind(PlanKind.NORMAL)
                .repository("git@internal:auth-service.git")
                .baseCommit("9b2f4e1")
                .build();
    }

    static Task codeTask(int order) {
        return Task.builder()
                .order(order)
                .type(TaskType.CODE_FEATURE)
                .risk(Risk.MEDIUM)
                .title("로그인 실패 메시지 변경")
                .target("src/auth/login.ts")
                .content(new TaskContent("--- a/src/auth/login.ts\n+++ b/src/auth/login.ts", null, null, null))
                .relatedDepartments(List.of(BACKEND_DEPT_ID))
                .requiredApprovers(List.of(new RequiredApprover(ApproverRule.ANYONE, 1, null)))
                .status(TaskStatus.PENDING)
                .build();
    }
}
