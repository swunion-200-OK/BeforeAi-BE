package site.siyoung.beforeaibe.service;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import site.siyoung.beforeaibe.TestcontainersConfiguration;
import site.siyoung.beforeaibe.dto.admin.AdminProof;
import site.siyoung.beforeaibe.dto.forbidden.*;
import site.siyoung.beforeaibe.entity.*;
import site.siyoung.beforeaibe.entity.enums.*;
import site.siyoung.beforeaibe.exception.*;
import site.siyoung.beforeaibe.repository.*;
import site.siyoung.beforeaibe.webshell.PolicyDeploymentClient;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

// 별도 컨텍스트·DB와 실제 커밋을 사용해 API 테스트의 롤백 트랜잭션으로는 확인할 수 없는 경계를 검증함.
@SpringBootTest(properties = "test.forbidden-transactions=true")
@Import(TestcontainersConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ForbiddenTransactionTest {
    @Autowired ForbiddenService service;
    @Autowired PolicyService policyService;
    @Autowired PolicyDeploymentService deployments;
    @Autowired ForbiddenProposalRepository proposals;
    @Autowired ForbiddenRuleRepository rules;
    @Autowired PolicyVersionRepository policies;
    @Autowired DepartmentRepository departments;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PolicyDeploymentClient client;
    UUID admin, manager, department;
    AdminProof proof;

    @BeforeEach
    void setup() {
        department = departments.saveAndFlush(Department.builder().name("테스트").build()).getId();
        admin = user("A", Role.ADMIN);
        manager = user("M", Role.DEPT_MANAGER);
        proof = new AdminProof(UUID.randomUUID(), "test-proof");
        succeed();
        policyService.applyPreset(admin, PolicyPreset.MEDIUM, proof);
    }
    UUID user(String number, Role role) {
        return users.saveAndFlush(User.builder().employeeNumber(number).name(number).passwordHash("test")
                .active(true).role(role).departmentId(department).passwordChangeRequired(false).build()).getId();
    }
    void succeed() {
        doAnswer(invocation -> {
            Map<String, Object> payload = invocation.getArgument(0);
            return new PolicyDeploymentClient.Result(((Number) payload.get("version")).intValue(), invocation.getArgument(2), true);
        }).when(client).apply(anyMap(), any(), anyString());
    }
    UUID propose() {
        return service.propose(manager, department, new ForbiddenProposalCreateRequest(TaskType.DEPLOY, "production", "동결")).id();
    }
    @AfterEach
    void cleanup() {
        jdbc.execute("truncate table users, departments, forbidden_proposals, forbidden_rules, policy_versions cascade");
    }

    @Test
    void 배포_실패해도_확정과_스냅샷은_커밋되고_동일한_서명으로_재전송한다() {
        UUID id = propose();
        reset(client);
        when(client.apply(anyMap(), any(), anyString())).thenThrow(new ApiException(ErrorCode.WEBSHELL_UNAVAILABLE));
        assertThatThrownBy(() -> service.confirm(admin, id, new ForbiddenProposalConfirmRequest(proof)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WEBSHELL_UNAVAILABLE));
        assertThat(proposals.findById(id).orElseThrow().getStatus()).isEqualTo(ForbiddenProposalStatus.CONFIRMED);
        assertThat(rules.count()).isEqualTo(1);
        var saved = policies.findTopByOrderByVersionDesc().orElseThrow();
        assertThat(saved.isWebshellApplied()).isFalse();
        var payload = saved.getPolicyPayload();
        assertThat(deployments.pendingVersion()).isEqualTo(saved.getId());
        succeed();
        assertThat(deployments.deploy(saved.getId())).isTrue();
        verify(client, times(2)).apply(eq(payload), eq(proof), eq(saved.getPolicyHash()));
        assertThat(policies.findById(saved.getId()).orElseThrow().isWebshellApplied()).isTrue();
        assertThat(deployments.pendingVersion()).isNull();
    }

    @Test
    void 버전과_해시가_다르거나_적용되지_않으면_완료_처리하지_않는다() {
        for (var result : List.of(new PolicyDeploymentClient.Result(999, "wrong", true),
                new PolicyDeploymentClient.Result(2, "wrong", true), new PolicyDeploymentClient.Result(2, "wrong", false))) {
            doReturn(result).when(client).apply(anyMap(), any(), anyString());
            UUID id = propose();
            assertThatThrownBy(() -> service.confirm(admin, id, new ForbiddenProposalConfirmRequest(proof)))
                    .isInstanceOf(ApiException.class);
            assertThat(policies.findTopByOrderByVersionDesc().orElseThrow().isWebshellApplied()).isFalse();
        }
        var older = policies.findAll().stream().filter(p -> p.getVersion() == 2).findFirst().orElseThrow();
        reset(client);
        assertThat(deployments.deploy(older.getId())).isFalse();
        verifyNoInteractions(client);
    }

    @Test
    void 동시에_확정해도_규칙과_버전은_하나만_생긴다() throws Exception {
        UUID id = propose();
        var outcomes = race(() -> {
            service.confirm(admin, id, new ForbiddenProposalConfirmRequest(proof)); return "CONFIRMED";
        }, () -> {
            service.confirm(admin, id, new ForbiddenProposalConfirmRequest(proof)); return "CONFIRMED";
        });
        assertThat(outcomes).containsExactlyInAnyOrder("CONFIRMED", "INVALID_STATE");
        assertThat(rules.count()).isEqualTo(1);
        assertThat(policies.count()).isEqualTo(2);
    }

    @Test
    void 확정과_반려가_경쟁해도_한_상태만_커밋된다() throws Exception {
        UUID id = propose();
        var outcomes = race(() -> {
            service.confirm(admin, id, new ForbiddenProposalConfirmRequest(proof)); return "CONFIRMED";
        }, () -> {
            service.reject(id, new ForbiddenProposalRejectRequest("반려")); return "REJECTED";
        });
        assertThat(outcomes).contains("INVALID_STATE");
        var status = proposals.findById(id).orElseThrow().getStatus();
        assertThat(rules.count()).isEqualTo(status == ForbiddenProposalStatus.CONFIRMED ? 1 : 0);
    }

    @Test
    void 동시에_첫_정책을_생성해도_버전이_겹치지_않는다() throws Exception {
        policies.deleteAllInBatch();
        var outcomes = race(() -> { policyService.applyPreset(admin, PolicyPreset.SMALL, proof); return "OK"; },
                () -> { policyService.applyPreset(admin, PolicyPreset.LARGE, proof); return "OK"; });
        assertThat(outcomes).containsOnly("OK");
        assertThat(policies.findAll()).extracting(PolicyVersion::getVersion).containsExactlyInAnyOrder(1, 2);
    }

    List<String> race(Callable<String> first, Callable<String> second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> outcome(start, first));
            var b = executor.submit(() -> outcome(start, second));
            start.countDown();
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
    }
    String outcome(CountDownLatch start, Callable<String> action) throws Exception {
        start.await();
        try { return action.call(); }
        catch (ApiException e) { return e.getErrorCode().name(); }
    }
}
