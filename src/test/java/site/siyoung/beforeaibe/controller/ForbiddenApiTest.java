package site.siyoung.beforeaibe.controller;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.annotation.Transactional;
import site.siyoung.beforeaibe.TestcontainersConfiguration;
import site.siyoung.beforeaibe.entity.*;
import site.siyoung.beforeaibe.entity.enums.*;
import site.siyoung.beforeaibe.repository.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class ForbiddenApiTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired DepartmentRepository departments;
    @Autowired ForbiddenProposalRepository proposals;
    @Autowired ForbiddenRuleRepository rules;
    @Autowired PolicyVersionRepository policies;
    @Autowired SigningKeyRepository signingKeys;
    @Autowired PasswordEncoder passwords;
    @Autowired EntityManager em;
    UUID departmentId;
    String admin, manager, employee, otherManager;
    static final String PROPOSAL = "{\"taskType\":\"DEPLOY\",\"target\":\"production\",\"reason\":\"운영 동결\"}";
    String proof;

    @BeforeEach
    void setup() throws Exception {
        departmentId = departments.saveAndFlush(Department.builder().name("개발").build()).getId();
        UUID other = departments.saveAndFlush(Department.builder().name("영업").build()).getId();
        saveAdmin(users, passwords, "FA");
        saveUser(users, passwords, "FM", Role.DEPT_MANAGER, departmentId);
        saveUser(users, passwords, "FE", Role.EMPLOYEE, departmentId);
        saveUser(users, passwords, "FO", Role.DEPT_MANAGER, other);
        admin = token("FA"); manager = token("FM"); employee = token("FE"); otherManager = token("FO");
        proof = "{\"keyId\":\"" + UUID.randomUUID() + "\",\"signature\":\"example\"}";
        mvc.perform(put("/api/v1/admin/policy/preset").with(internal()).header("Authorization", bearer(admin))
                .contentType("application/json").content("{\"preset\":\"MEDIUM\",\"adminProof\":" + proof + "}"))
                .andExpect(status().isOk());
    }
    String token(String number) throws Exception { return json(loginInternal(mvc, number), "$.accessToken"); }
    String proposalPath() { return "/api/v1/departments/" + departmentId + "/forbidden-proposals"; }
    String rulesPath() { return "/api/v1/departments/" + departmentId + "/forbidden-rules"; }
    String propose() throws Exception {
        return json(mvc.perform(post(proposalPath()).with(internal()).header("Authorization", bearer(manager))
                .contentType("application/json").content(PROPOSAL)).andExpect(status().isCreated()).andReturn(), "$.id");
    }
    ResultActions confirm(String id) throws Exception {
        return mvc.perform(post("/api/v1/admin/forbidden-proposals/" + id + "/confirm").with(internal())
                .header("Authorization", bearer(admin)).contentType("application/json").content("{\"adminProof\":" + proof + "}"));
    }
    String add() throws Exception {
        return json(mvc.perform(post("/api/v1/admin/forbidden-rules").with(internal()).header("Authorization", bearer(admin))
                .contentType("application/json").content("{\"departmentId\":\"" + departmentId
                        + "\",\"taskType\":\"DEPLOY\",\"target\":\"production\",\"reason\":\"직접 추가\",\"adminProof\":" + proof + "}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("CONFIRMED")).andReturn(), "$.id");
    }

    @Test
    void 제안_확정으로_규칙과_정책을_하나씩_만든다() throws Exception {
        String id = propose();
        assertThat(rules.count()).isZero();
        confirm(id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.policyVersion").value(2)).andExpect(jsonPath("$.rule.target").value("production"));
        confirm(id).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
        assertThat(rules.count()).isEqualTo(1);
        assertThat(policies.count()).isEqualTo(2);
        em.flush(); em.clear();
        assertThat(policies.findTopByOrderByVersionDesc().orElseThrow().getPolicyPayload().get("forbiddenRules"))
                .asList().hasSize(1);
    }

    @Test
    void 반려_사유는_원문과_별도로_보존하며_확정할_수_없다() throws Exception {
        String id = propose();
        mvc.perform(post("/api/v1/admin/forbidden-proposals/" + id + "/reject").with(internal())
                .header("Authorization", bearer(admin)).contentType("application/json").content("{\"reason\":\"기존 정책 충분\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        confirm(id).andExpect(status().isConflict());
        em.flush(); em.clear();
        var proposal = proposals.findById(UUID.fromString(id)).orElseThrow();
        assertThat(proposal.getReason()).isEqualTo("운영 동결");
        assertThat(proposal.getRejectionReason()).isEqualTo("기존 정책 충분");
        assertThat(rules.count()).isZero();
    }

    @Test
    void 해제_요청_후에도_규칙을_조회하며_다음_서명_정책에_포함한다() throws Exception {
        String id = add();
        mvc.perform(post("/api/v1/admin/forbidden-rules/" + id + "/release").with(internal())
                .header("Authorization", bearer(admin)).contentType("application/json").content("{\"reason\":\"동결 종료\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RELEASE_PENDING"));
        assertThat(policies.count()).isEqualTo(2);
        mvc.perform(get(rulesPath()).with(external()).header("Authorization", bearer(employee)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].status").value("RELEASE_PENDING"));
        mvc.perform(post("/api/v1/admin/forbidden-rules/" + id + "/release").with(internal())
                .header("Authorization", bearer(admin)).contentType("application/json").content("{\"reason\":\"재요청\"}"))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/v1/admin/policy/preset").with(internal()).header("Authorization", bearer(admin))
                .contentType("application/json").content("{\"preset\":\"LARGE\",\"adminProof\":" + proof + "}"))
                .andExpect(status().isOk());
        em.flush(); em.clear();
        var payload = policies.findTopByOrderByVersionDesc().orElseThrow().getPolicyPayload();
        assertThat(((Map<?, ?>)((List<?>)payload.get("forbiddenRules")).getFirst()).get("status")).isEqualTo("RELEASE_PENDING");
        assertThat(rules.findById(UUID.fromString(id)).orElseThrow().getReleaseReason()).isEqualTo("동결 종료");
    }

    @Test
    void 서명_있는_해제_요청은_즉시_새_정책을_생성한다() throws Exception {
        String id = add();
        mvc.perform(post("/api/v1/admin/forbidden-rules/" + id + "/release").with(internal())
                .header("Authorization", bearer(admin)).contentType("application/json")
                .content("{\"reason\":\"동결 종료\",\"adminProof\":" + proof + "}"))
                .andExpect(status().isOk());
        assertThat(policies.count()).isEqualTo(3);
    }

    @Test
    void 소속_부서장만_내부망에서_제안한다() throws Exception {
        for (String token : List.of(employee, otherManager, admin)) {
            mvc.perform(post(proposalPath()).with(internal()).header("Authorization", bearer(token))
                    .contentType("application/json").content(PROPOSAL))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN_ROLE"));
        }
        mvc.perform(post(proposalPath()).with(external()).header("Authorization", bearer(manager))
                .contentType("application/json").content(PROPOSAL)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_NETWORK_ONLY"));
        mvc.perform(post(proposalPath()).with(internal()).contentType("application/json").content(PROPOSAL))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 관리자_목록과_변경은_내부망_ADMIN만_허용한다() throws Exception {
        mvc.perform(get("/api/v1/admin/forbidden-proposals").with(internal()).header("Authorization", bearer(manager)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/forbidden-proposals").with(external()).header("Authorization", bearer(admin)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("INTERNAL_NETWORK_ONLY"));
    }

    @Test
    void 제안_목록은_필터와_커서로_중복_없이_조회한다() throws Exception {
        for (int i = 0; i < 3; i++) propose();
        // 동일 시각에서도 UUID를 보조 정렬 키로 사용해야 함.
        em.createQuery("update ForbiddenProposal f set f.createdAt = :time").setParameter("time", java.time.Instant.parse("2026-01-01T00:00:00Z")).executeUpdate();
        em.clear();
        var first = mvc.perform(get("/api/v1/admin/forbidden-proposals").param("limit", "2").param("status", "OPEN")
                .with(internal()).header("Authorization", bearer(admin))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2)).andReturn();
        var second = mvc.perform(get("/api/v1/admin/forbidden-proposals").param("limit", "2").param("status", "OPEN")
                .param("cursor", json(first, "$.nextCursor")).with(internal()).header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.nextCursor").isEmpty()).andReturn();
        assertThat(json(second, "$.items[0].id")).isNotIn(json(first, "$.items[0].id"), json(first, "$.items[1].id"));
        mvc.perform(get("/api/v1/admin/forbidden-proposals").param("status", "REJECTED").with(internal())
                .header("Authorization", bearer(admin))).andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void 승인_기준표를_바꿔도_금지_규칙과_등록된_공개키를_보존한다() throws Exception {
        User user = users.findByEmployeeNumber("FA").orElseThrow();
        signingKeys.saveAndFlush(SigningKey.builder().user(user).algorithm("Ed25519")
                .publicKey("test-registered-public-key").status(SigningKeyStatus.REGISTERED).build());
        String ruleId = add();
        mvc.perform(put("/api/v1/admin/approval-matrix").with(internal()).header("Authorization", bearer(admin))
                .contentType("application/json").content("""
                        {"rules":[{"taskType":"DEPLOY","risk":"HIGH","requiredApprovers":[{"rule":"PROJECT_OWNER","count":1}]}],
                         "adminProof":%s}
                        """.formatted(proof))).andExpect(status().isOk());
        em.flush(); em.clear();
        var payload = policies.findTopByOrderByVersionDesc().orElseThrow().getPolicyPayload();
        assertThat(((Map<?, ?>)((List<?>)payload.get("forbiddenRules")).getFirst()).get("id")).isEqualTo(ruleId);
        assertThat(((Map<?, ?>)((List<?>)payload.get("signingKeys")).getFirst()).get("publicKey"))
                .isEqualTo("test-registered-public-key");
        assertThat(((Map<?, ?>)((List<?>)payload.get("approvalMatrix")).getFirst()).get("taskType")).isEqualTo("DEPLOY");
    }

    @Test
    void 잘못된_입력과_미존재_대상을_거절한다() throws Exception {
        for (String body : List.of("{}", PROPOSAL.replace("DEPLOY", "UNKNOWN"), PROPOSAL.replace("production", " "),
                PROPOSAL.replace("production", "a".repeat(1025)))) {
            mvc.perform(post(proposalPath()).with(internal()).header("Authorization", bearer(manager))
                    .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        for (String query : List.of("cursor=bad", "limit=0", "limit=101")) {
            mvc.perform(get(rulesPath() + "?" + query).with(internal()).header("Authorization", bearer(employee)))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/departments/" + UUID.randomUUID() + "/forbidden-rules").with(internal())
                .header("Authorization", bearer(employee))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/departments/invalid/forbidden-rules").with(internal())
                .header("Authorization", bearer(employee))).andExpect(status().isBadRequest());
        confirm(UUID.randomUUID().toString()).andExpect(status().isNotFound());
    }
}
