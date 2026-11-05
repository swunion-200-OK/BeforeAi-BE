package site.siyoung.beforeaibe.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import site.siyoung.beforeaibe.TestcontainersConfiguration;
import site.siyoung.beforeaibe.config.JpaAuditingConfig;
import site.siyoung.beforeaibe.entity.Plan;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.Task;
import site.siyoung.beforeaibe.entity.value.RequiredApprover;
import site.siyoung.beforeaibe.entity.value.TaskContent;
import site.siyoung.beforeaibe.entity.enums.ApproverRule;
import site.siyoung.beforeaibe.entity.enums.Risk;
import site.siyoung.beforeaibe.entity.enums.TaskStatus;
import site.siyoung.beforeaibe.entity.enums.TaskType;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
class PlanRepositoryTest {

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private TestEntityManager em;

    @Test
    void Plan과_Task를_함께_저장하면_Task가_order_순서로_조회된다() {
        Request request = RepositoryTestSupport.persistRequest(em);
        UUID infraDeptId = UUID.randomUUID();
        List<UUID> owners = List.of(UUID.randomUUID(), UUID.randomUUID());

        Plan plan = RepositoryTestSupport.plan(request);
        plan.addTask(Task.builder()
                .order(2)
                .type(TaskType.DEPLOY)
                .risk(Risk.HIGH)
                .title("스테이징 배포")
                .target("staging")
                .content(new TaskContent(null, "./deploy.sh staging", null, null))
                .relatedDepartments(List.of(RepositoryTestSupport.BACKEND_DEPT_ID, infraDeptId))
                .requiredApprovers(List.of(
                        new RequiredApprover(ApproverRule.PROJECT_OWNER, 1, owners),
                        new RequiredApprover(ApproverRule.DEPT_MANAGER, 1, null)))
                .status(TaskStatus.PENDING)
                .build());
        plan.addTask(RepositoryTestSupport.codeTask(1));

        Plan saved = planRepository.saveAndFlush(plan);
        em.clear();

        Plan found = planRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getRequest().getId()).isEqualTo(request.getId());
        assertThat(found.getPlanHash()).isNull();
        assertThat(found.getExecutorSignature()).isNull();
        assertThat(found.getTasks()).extracting(Task::getOrder).containsExactly(1, 2);

        Task deploy = found.getTasks().get(1);
        assertThat(deploy.getContent()).isEqualTo(new TaskContent(null, "./deploy.sh staging", null, null));
        assertThat(deploy.getRelatedDepartments())
                .containsExactly(RepositoryTestSupport.BACKEND_DEPT_ID, infraDeptId);
        assertThat(deploy.getRequiredApprovers()).containsExactly(
                new RequiredApprover(ApproverRule.PROJECT_OWNER, 1, owners),
                new RequiredApprover(ApproverRule.DEPT_MANAGER, 1, null));
    }

    @Test
    void 같은_Plan_안에서_order가_겹치면_저장되지_않는다() {
        Request request = RepositoryTestSupport.persistRequest(em);

        Plan plan = RepositoryTestSupport.plan(request);
        plan.addTask(RepositoryTestSupport.codeTask(1));
        plan.addTask(RepositoryTestSupport.codeTask(1));

        assertThatThrownBy(() -> planRepository.saveAndFlush(plan))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void Task_목록은_addTask로만_추가할_수_있다() {
        Plan plan = RepositoryTestSupport.plan(RepositoryTestSupport.request(UUID.randomUUID(), UUID.randomUUID()));

        assertThatThrownBy(() -> plan.getTasks().add(RepositoryTestSupport.codeTask(1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void jsonb에는_값이_없는_필드를_저장하지_않는다() {
        Request request = RepositoryTestSupport.persistRequest(em);
        Plan plan = RepositoryTestSupport.plan(request);
        plan.addTask(RepositoryTestSupport.codeTask(1));
        Plan saved = planRepository.saveAndFlush(plan);

        Object[] row = (Object[]) em.getEntityManager()
                .createNativeQuery("SELECT content::text, required_approvers::text FROM tasks WHERE plan_id = :planId")
                .setParameter("planId", saved.getId())
                .getSingleResult();

        assertThat((String) row[0]).contains("\"diff\"").doesNotContain("command", "query", "body");
        assertThat((String) row[1]).contains("\"rule\"", "\"count\"").doesNotContain("candidates");
    }
}
