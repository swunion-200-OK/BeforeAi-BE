package site.siyoung.beforeaibe.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import site.siyoung.beforeaibe.TestcontainersConfiguration;
import site.siyoung.beforeaibe.entity.Approval;
import site.siyoung.beforeaibe.entity.Department;
import site.siyoung.beforeaibe.entity.Plan;
import site.siyoung.beforeaibe.entity.Project;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.Task;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.ApproverRule;
import site.siyoung.beforeaibe.entity.enums.Network;
import site.siyoung.beforeaibe.entity.enums.PlanKind;
import site.siyoung.beforeaibe.entity.enums.RequestSource;
import site.siyoung.beforeaibe.entity.enums.RequestStatus;
import site.siyoung.beforeaibe.entity.enums.Risk;
import site.siyoung.beforeaibe.entity.enums.Role;
import site.siyoung.beforeaibe.entity.enums.TaskStatus;
import site.siyoung.beforeaibe.entity.enums.TaskType;
import site.siyoung.beforeaibe.entity.value.ApprovalEnvelope;
import site.siyoung.beforeaibe.entity.value.RequiredApprover;
import site.siyoung.beforeaibe.entity.value.TaskContent;
import site.siyoung.beforeaibe.repository.ApprovalRepository;
import site.siyoung.beforeaibe.repository.DepartmentRepository;
import site.siyoung.beforeaibe.repository.PlanRepository;
import site.siyoung.beforeaibe.repository.ProjectRepository;
import site.siyoung.beforeaibe.repository.RequestRepository;
import site.siyoung.beforeaibe.repository.UserRepository;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.PASSWORD;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.bearer;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.external;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.internal;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.json;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.loginInternal;

// 승인 HTTP 호출은 전량 승인 시 별도 트랜잭션(REQUIRES_NEW)으로 웹쉘 실행을 요청하므로,
// 이 테스트는 승인 기록을 리포지토리로 직접 넣고 결재 문서 쪽 동작만 검증함
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class ApprovalDocumentApiTest {

    private static final String PROMPT = "로그인 실패 시 에러 메시지를 바꾸고 스테이징에 배포해줘";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private RequestRepository requestRepository;

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private ApprovalRepository approvalRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Project project;
    private User requester;
    private User reviewer;
    private User manager;
    private User outsider;

    @BeforeEach
    void setUp() {
        Department dev = departmentRepository.saveAndFlush(Department.builder().name("개발팀").build());
        Department ops = departmentRepository.saveAndFlush(Department.builder().name("운영팀").build());
        requester = saveUser("EMP001", "김기안", dev);
        reviewer = saveUser("EMP101", "이검토", dev);
        manager = saveUser("EMP102", "박부장", ops);
        outsider = saveUser("EMP002", "최외부", ops);
        project = projectRepository.saveAndFlush(Project.builder()
                .name("인증 서비스")
                .department(dev)
                .repository("git@internal:auth-service.git")
                .build());
    }

    private User saveUser(String employeeNumber, String name, Department department) {
        return userRepository.saveAndFlush(User.builder()
                .employeeNumber(employeeNumber)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .name(name)
                .role(Role.EMPLOYEE)
                .departmentId(department.getId())
                .active(true)
                .passwordChangeRequired(false)
                .build());
    }

    private String token(User user) throws Exception {
        return bearer(json(loginInternal(mockMvc, user.getEmployeeNumber()), "$.accessToken"));
    }

    private Task task(int order, String title, Risk risk, User approver) {
        return Task.builder()
                .order(order)
                .type(TaskType.CODE_FEATURE)
                .risk(risk)
                .title(title)
                .target("src/auth/login.ts")
                .content(new TaskContent("--- a/src/auth/login.ts\n+++ b/src/auth/login.ts", null, null, null))
                .relatedDepartments(List.of())
                .requiredApprovers(List.of(new RequiredApprover(ApproverRule.SAME_DEPT_OTHER, 1,
                        List.of(approver.getId()))))
                .status(TaskStatus.PENDING)
                .build();
    }

    // 리뷰 중(REVIEWING) 요청. 작업 1은 reviewer, 작업 2는 manager가 승인권자
    private Request saveRequest(RequestSource source) {
        Request request = requestRepository.saveAndFlush(Request.builder()
                .projectId(project.getId())
                .requesterId(requester.getId())
                .prompt(PROMPT)
                .source(source)
                .status(RequestStatus.REVIEWING)
                .analysisAttempts(1)
                .build());
        Plan plan = Plan.builder()
                .request(request)
                .kind(PlanKind.NORMAL)
                .repository("git@internal:auth-service.git")
                .baseCommit("9b2f4e1")
                .build();
        plan.addTask(task(1, "로그인 실패 메시지 변경", Risk.MEDIUM, reviewer));
        plan.addTask(task(2, "스테이징 배포", Risk.HIGH, manager));
        Plan saved = planRepository.saveAndFlush(plan);
        ReflectionTestUtils.setField(request, "currentPlanId", saved.getId());
        return requestRepository.saveAndFlush(request);
    }

    private Request submittedRequest() throws Exception {
        Request request = saveRequest(RequestSource.PROMPT);
        submit(request);
        return request;
    }

    private void submit(Request request) throws Exception {
        mockMvc.perform(post("/api/v1/requests/{id}/submit", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token(requester)))
                .andExpect(status().isOk());
    }

    private Plan currentPlan(Request request) {
        Request reloaded = requestRepository.findById(request.getId()).orElseThrow();
        return planRepository.findWithTasksById(reloaded.getCurrentPlanId()).orElseThrow();
    }

    private Task taskTitled(Plan plan, String title) {
        return plan.getTasks().stream().filter(task -> task.getTitle().equals(title)).findFirst().orElseThrow();
    }

    // POST /requests/{id}/approvals가 남기는 것과 같은 승인 기록
    private void approve(Request request, Plan plan, User approver, Task task) {
        approvalRepository.saveAndFlush(Approval.builder()
                .request(requestRepository.findById(request.getId()).orElseThrow())
                .plan(plan)
                .approverId(approver.getId())
                .keyId(UUID.randomUUID())
                .planHash(plan.getPlanHash())
                .taskIds(List.of(task.getId()))
                .network(Network.INTERNAL)
                .envelope(ApprovalEnvelope.builder().alg("HPKE-X25519-AES256GCM").encapsulatedKey("ZW5j")
                        .ciphertext("Y2lwaGVy").build())
                .build());
    }

    private void approveAll(Request request) {
        Plan plan = currentPlan(request);
        approve(request, plan, reviewer, taskTitled(plan, "로그인 실패 메시지 변경"));
        approve(request, plan, manager, taskTitled(plan, "스테이징 배포"));
    }

    private String pdfText(MvcResult result) throws IOException {
        PdfReader reader = new PdfReader(result.getResponse().getContentAsByteArray());
        try {
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            StringBuilder text = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                text.append(extractor.getTextFromPage(page));
            }
            return text.toString();
        } finally {
            reader.close();
        }
    }

    @Test
    void 제출하면_결재_문서_v1이_생기고_승인_화면에서_미리보기_PDF를_받는다() throws Exception {
        Request request = submittedRequest();
        String reviewerToken = token(reviewer);

        mockMvc.perform(get("/api/v1/requests/{id}/approval-document/versions", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].version").value(1))
                .andExpect(jsonPath("$.items[0].planId").value(currentPlan(request).getId().toString()))
                .andExpect(jsonPath("$.items[0].planHash").value(currentPlan(request).getPlanHash()))
                .andExpect(jsonPath("$.items[0].status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.items[0].downloadable").value(false));

        MvcResult result = mockMvc.perform(get("/api/v1/requests/{id}/approval-document", request.getId())
                        .with(internal())
                        .header(HttpHeaders.AUTHORIZATION, reviewerToken))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.startsWith("inline")))
                .andReturn();
        assertThat(pdfText(result)).contains("결재란", "로그인 실패 메시지 변경", "스테이징 배포", "미결", "김기안",
                currentPlan(request).getPlanHash());
    }

    @Test
    void 승인하면_해당_작업_칸에만_도장이_찍힌다() throws Exception {
        Request request = submittedRequest();
        Plan plan = currentPlan(request);
        approve(request, plan, reviewer, taskTitled(plan, "로그인 실패 메시지 변경"));

        MvcResult result = mockMvc.perform(get("/api/v1/requests/{id}/approval-document", request.getId())
                        .with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token(requester)))
                .andExpect(status().isOk())
                .andReturn();
        String text = pdfText(result);
        assertThat(text).contains("이검토", "미결");
        assertThat(text).doesNotContain("박부장");
    }

    @Test
    void 필요한_승인이_끝나기_전에는_PDF를_받을_수_없다() throws Exception {
        Request request = submittedRequest();
        Plan plan = currentPlan(request);
        approve(request, plan, reviewer, taskTitled(plan, "로그인 실패 메시지 변경"));

        mockMvc.perform(get("/api/v1/requests/{id}/approval-document/pdf", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token(requester))
                        .accept("application/pdf"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    @Test
    void 작업별_승인자가_모두_승인하면_도장이_찍힌_PDF를_받는다() throws Exception {
        Request request = submittedRequest();
        approveAll(request);

        MvcResult result = mockMvc.perform(get("/api/v1/requests/{id}/approval-document/pdf", request.getId())
                        .with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token(requester)))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.startsWith("attachment")))
                .andReturn();
        String text = pdfText(result);
        assertThat(text).contains("이검토", "박부장", "결재 완료");
        assertThat(text).doesNotContain("미결", "실행 결과");
    }

    @Test
    void 실행_뒤에_받으면_작업별_최종_상태가_담긴다() throws Exception {
        Request request = submittedRequest();
        approveAll(request);
        Plan plan = currentPlan(request);
        taskTitled(plan, "로그인 실패 메시지 변경").changeStatus(TaskStatus.SUCCEEDED);
        taskTitled(plan, "스테이징 배포").changeStatus(TaskStatus.FAILED);
        planRepository.saveAndFlush(plan);

        MvcResult result = mockMvc.perform(get("/api/v1/requests/{id}/approval-document/pdf", request.getId())
                        .with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token(requester)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(pdfText(result)).contains("실행 결과", "성공", "실패");
    }

    @Test
    void 승인_도중_Plan을_바꿔_다시_제출하면_v2가_되고_v1은_기록으로_남는다() throws Exception {
        Request request = submittedRequest();
        Plan v1 = currentPlan(request);
        approve(request, v1, reviewer, taskTitled(v1, "로그인 실패 메시지 변경"));
        String requesterToken = token(requester);

        // 승인 대기 중 배포 작업 삭제 → 새 Plan으로 재분석. AI 분석기 연동 전이라 검토 상태로 직접 돌려 재제출함
        mockMvc.perform(patch("/api/v1/requests/{id}/plan", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, requesterToken)
                        .contentType("application/json")
                        .content("""
                                {"basePlanId": "%s", "changes": [{"op": "DELETE", "taskId": "%s"}]}
                                """.formatted(v1.getId(), taskTitled(v1, "스테이징 배포").getId())))
                .andExpect(status().isAccepted());

        // 다시 제출하기 전까지 v1은 최신 판으로 남되 "Plan 수정 중"으로 표시되고 받을 수 없음
        mockMvc.perform(get("/api/v1/requests/{id}/approval-document/versions", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, requesterToken))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].status").value("REVISING"))
                .andExpect(jsonPath("$.items[0].downloadable").value(false));
        MvcResult revising = mockMvc.perform(get("/api/v1/requests/{id}/approval-document", request.getId())
                        .with(internal())
                        .header(HttpHeaders.AUTHORIZATION, requesterToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(pdfText(revising)).contains("Plan 수정 중", "제 2 판이 됩니다");

        Request revised = requestRepository.findById(request.getId()).orElseThrow();
        ReflectionTestUtils.setField(revised, "status", RequestStatus.REVIEWING);
        requestRepository.saveAndFlush(revised);
        submit(request);

        mockMvc.perform(get("/api/v1/requests/{id}/approval-document/versions", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, requesterToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].version").value(2))
                .andExpect(jsonPath("$.items[0].status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.items[1].version").value(1))
                .andExpect(jsonPath("$.items[1].planId").value(v1.getId().toString()))
                .andExpect(jsonPath("$.items[1].status").value("SUPERSEDED"));

        // 이전 판은 그 판의 도장과 함께 기록으로 볼 수 있음
        MvcResult old = mockMvc.perform(get("/api/v1/requests/{id}/approval-document/versions/{version}",
                        request.getId(), 1).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, requesterToken))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andReturn();
        assertThat(pdfText(old)).contains("이검토", "스테이징 배포", "이전 판");

        // 새 판은 이전 서명을 이어받지 않음
        MvcResult current = mockMvc.perform(get("/api/v1/requests/{id}/approval-document", request.getId())
                        .with(internal())
                        .header(HttpHeaders.AUTHORIZATION, requesterToken))
                .andExpect(status().isOk())
                .andReturn();
        String text = pdfText(current);
        assertThat(text).contains("제 2 판", "미결");
        assertThat(text).doesNotContain("이검토", "스테이징 배포");
    }

    @Test
    void 실패_작업_재시도_Plan을_수정_요청_뒤_다시_제출해도_판이_늘지_않는다() throws Exception {
        Request request = submittedRequest();
        Plan v1 = currentPlan(request);
        taskTitled(v1, "로그인 실패 메시지 변경").changeStatus(TaskStatus.SUCCEEDED);
        taskTitled(v1, "스테이징 배포").changeStatus(TaskStatus.FAILED);
        planRepository.saveAndFlush(v1);
        Request partial = requestRepository.findById(request.getId()).orElseThrow();
        ReflectionTestUtils.setField(partial, "status", RequestStatus.PARTIALLY_COMPLETED);
        requestRepository.saveAndFlush(partial);
        String requesterToken = token(requester);

        mockMvc.perform(post("/api/v1/requests/{id}/retry", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, requesterToken))
                .andExpect(status().isOk());
        Plan retryPlan = currentPlan(request);
        assertThat(retryPlan.getId()).isNotEqualTo(v1.getId());

        mockMvc.perform(post("/api/v1/requests/{id}/change-requests", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token(manager))
                        .contentType("application/json")
                        .content("""
                                {"planId": "%s", "taskIds": ["%s"], "reason": "배포 대상을 확인해 주세요."}"""
                                .formatted(retryPlan.getId(), retryPlan.getTasks().getFirst().getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEWING"));
        submit(request);

        mockMvc.perform(get("/api/v1/requests/{id}/approval-document/versions", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, requesterToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].planId").value(v1.getId().toString()));
    }

    @Test
    void 없는_판은_찾을_수_없다() throws Exception {
        Request request = submittedRequest();

        mockMvc.perform(get("/api/v1/requests/{id}/approval-document/versions/{version}", request.getId(), 9)
                        .with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token(requester)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void 거부된_요청은_승인이_끝났어도_PDF를_받을_수_없다() throws Exception {
        Request request = submittedRequest();
        approveAll(request);
        Request rejected = requestRepository.findById(request.getId()).orElseThrow();
        rejected.reject();
        requestRepository.saveAndFlush(rejected);

        mockMvc.perform(get("/api/v1/requests/{id}/approval-document/pdf", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token(requester)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    @Test
    void 에디터_변경_요청은_결재_문서_대상이_아니다() throws Exception {
        Request request = saveRequest(RequestSource.EDITOR);
        submit(request);

        mockMvc.perform(get("/api/v1/requests/{id}/approval-document", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token(requester)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void 제출_전_요청은_결재_문서가_없다() throws Exception {
        Request request = saveRequest(RequestSource.PROMPT);

        mockMvc.perform(get("/api/v1/requests/{id}/approval-document/versions", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token(requester)))
                .andExpect(status().isNotFound());
    }

    @Test
    void 요청을_볼_수_없는_사용자는_결재_문서를_볼_수_없다() throws Exception {
        Request request = submittedRequest();

        mockMvc.perform(get("/api/v1/requests/{id}/approval-document", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token(outsider)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN_ROLE"));
    }

    @Test
    void 외부망에서도_미리보기와_PDF를_받는다() throws Exception {
        Request request = submittedRequest();
        approveAll(request);
        String requesterToken = token(requester);

        mockMvc.perform(get("/api/v1/requests/{id}/approval-document", request.getId()).with(external())
                        .header(HttpHeaders.AUTHORIZATION, requesterToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/requests/{id}/approval-document/pdf", request.getId()).with(external())
                        .header(HttpHeaders.AUTHORIZATION, requesterToken))
                .andExpect(status().isOk());
    }
}
