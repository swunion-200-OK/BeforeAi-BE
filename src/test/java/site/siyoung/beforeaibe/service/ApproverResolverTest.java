package site.siyoung.beforeaibe.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import site.siyoung.beforeaibe.entity.Department;
import site.siyoung.beforeaibe.entity.PolicyVersion;
import site.siyoung.beforeaibe.entity.Project;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.ApproverRule;
import site.siyoung.beforeaibe.entity.enums.PolicyPreset;
import site.siyoung.beforeaibe.entity.enums.Risk;
import site.siyoung.beforeaibe.entity.enums.Role;
import site.siyoung.beforeaibe.entity.enums.TaskType;
import site.siyoung.beforeaibe.entity.value.RequiredApprover;
import site.siyoung.beforeaibe.repository.PolicyVersionRepository;
import site.siyoung.beforeaibe.repository.UserRepository;
import site.siyoung.beforeaibe.support.PresetApprovalMatrix;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApproverResolverTest {

    private final PolicyVersionRepository policies = mock(PolicyVersionRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ApproverResolver resolver = new ApproverResolver(policies, users);

    private final UUID departmentId = UUID.randomUUID();
    private User requester;
    private User colleague;
    private User manager;
    private User admin;
    private Project project;

    private User user(Role role) {
        User user = User.builder().role(role).departmentId(departmentId).active(true).build();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    @BeforeEach
    void setUp() {
        requester = user(Role.EMPLOYEE);
        colleague = user(Role.EMPLOYEE);
        manager = user(Role.DEPT_MANAGER);
        admin = user(Role.ADMIN);
        Department department = Department.builder().name("개발팀").build();
        ReflectionTestUtils.setField(department, "id", departmentId);
        project = Project.builder().name("p").department(department).repository("r").build();
        project.addOwner(colleague);
        project.addOwner(requester);
        when(policies.findTopByOrderByVersionDesc()).thenReturn(Optional.empty());
        when(users.findByDepartmentIdAndActiveTrue(departmentId)).thenReturn(List.of(requester, colleague, manager));
        when(users.findByDepartmentIdAndRoleAndActiveTrue(departmentId, Role.DEPT_MANAGER)).thenReturn(List.of(manager));
        when(users.findByRoleAndActiveTrue(Role.ADMIN)).thenReturn(List.of(admin));
    }

    private List<RequiredApprover> resolve(TaskType type, Risk risk) {
        return resolver.resolve(type, risk, project, requester.getId());
    }

    @Test
    void 기준표에_없는_조합은_위험도별_기본값을_쓴다() {
        assertThat(resolve(TaskType.CODE_MINOR, Risk.LOW).getFirst().rule()).isEqualTo(ApproverRule.ANYONE);
        assertThat(resolve(TaskType.CODE_MINOR, Risk.MEDIUM).getFirst().rule()).isEqualTo(ApproverRule.SAME_DEPT_OTHER);
        assertThat(resolve(TaskType.CODE_MINOR, Risk.HIGH).getFirst().rule()).isEqualTo(ApproverRule.PROJECT_OWNER);
    }

    @Test
    void 금지_작업은_승인자가_없다() {
        assertThat(resolve(TaskType.DEPLOY, Risk.FORBIDDEN)).isEmpty();
    }

    @Test
    void 요청자는_ANYONE이_아닌_규칙의_후보에서_빠진다() {
        assertThat(resolve(TaskType.CODE_MINOR, Risk.MEDIUM).getFirst().candidates())
                .containsExactlyInAnyOrder(colleague.getId(), manager.getId());
        assertThat(resolve(TaskType.CODE_MINOR, Risk.HIGH).getFirst().candidates())
                .containsExactly(colleague.getId());
    }

    @Test
    void 현재_정책_기준표를_따른다() {
        PolicyVersion policy = mock(PolicyVersion.class);
        when(policy.getApprovalMatrix()).thenReturn(PresetApprovalMatrix.of(PolicyPreset.LARGE));
        when(policies.findTopByOrderByVersionDesc()).thenReturn(Optional.of(policy));

        List<RequiredApprover> approvers = resolve(TaskType.DEPLOY, Risk.HIGH);

        assertThat(approvers).extracting(RequiredApprover::rule)
                .containsExactly(ApproverRule.DEPT_MANAGER, ApproverRule.PROJECT_OWNER);
        assertThat(approvers.getFirst().candidates()).containsExactly(manager.getId());
    }

    @Test
    void 후보가_요청자뿐이면_관리자에게_넘긴다() {
        when(users.findByDepartmentIdAndActiveTrue(departmentId)).thenReturn(List.of(requester));

        assertThat(resolve(TaskType.CODE_MINOR, Risk.MEDIUM).getFirst().candidates()).containsExactly(admin.getId());
    }
}
