package site.siyoung.beforeaibe.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import site.siyoung.beforeaibe.ai.dto.AiTask;
import site.siyoung.beforeaibe.ai.dto.AnalysisInput;
import site.siyoung.beforeaibe.ai.dto.AnalysisResult;
import site.siyoung.beforeaibe.entity.Department;
import site.siyoung.beforeaibe.entity.Plan;
import site.siyoung.beforeaibe.entity.Project;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.SpecVersion;
import site.siyoung.beforeaibe.entity.Task;
import site.siyoung.beforeaibe.entity.enums.RequestSource;
import site.siyoung.beforeaibe.entity.enums.RequestStatus;
import site.siyoung.beforeaibe.entity.enums.Risk;
import site.siyoung.beforeaibe.entity.enums.TaskType;
import site.siyoung.beforeaibe.entity.value.TaskContent;
import site.siyoung.beforeaibe.repository.ForbiddenRuleRepository;
import site.siyoung.beforeaibe.repository.PlanRepository;
import site.siyoung.beforeaibe.repository.ProjectRepository;
import site.siyoung.beforeaibe.repository.RequestRepository;
import site.siyoung.beforeaibe.repository.SpecVersionRepository;
import site.siyoung.beforeaibe.service.ApproverResolver;
import site.siyoung.beforeaibe.service.RequestStreamService;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisPersistenceServiceTest {

    private final RequestRepository requests = mock(RequestRepository.class);
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final PlanRepository plans = mock(PlanRepository.class);
    private final ForbiddenRuleRepository forbidden = mock(ForbiddenRuleRepository.class);
    private final SpecVersionRepository specs = mock(SpecVersionRepository.class);
    private final ApproverResolver approvers = mock(ApproverResolver.class);
    private final AnalysisPersistenceService service = new AnalysisPersistenceService(requests, projects, plans,
            forbidden, mock(RequestStreamService.class), approvers, specs);

    private final UUID requestId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private Request request;

    @BeforeEach
    void setUp() {
        request = Request.builder().projectId(projectId).requesterId(UUID.randomUUID()).prompt("p")
                .source(RequestSource.PROMPT).status(RequestStatus.ANALYZING).analysisAttempts(1).build();
        ReflectionTestUtils.setField(request, "id", requestId);
        Department department = Department.builder().name("d").build();
        ReflectionTestUtils.setField(department, "id", UUID.randomUUID());
        Project project = Project.builder().name("n").department(department).repository("r").build();
        ReflectionTestUtils.setField(project, "id", projectId);
        when(requests.findById(requestId)).thenReturn(Optional.of(request));
        when(requests.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
        when(projects.findById(projectId)).thenReturn(Optional.of(project));
        when(forbidden.firstPage(any(), any())).thenReturn(List.of());
        when(approvers.resolve(any(), any(), any(), any())).thenReturn(List.of());
        when(plans.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void 재분석_입력에_보관한_지시와_이전_Plan과_기획서를_담는다() {
        UUID planId = UUID.randomUUID();
        Plan prior = Plan.builder().request(request).build();
        prior.addTask(Task.builder().order(0).type(TaskType.CODE_FEATURE).risk(Risk.MEDIUM).title("로그인 수정")
                .target("a.ts").content(new TaskContent("diff-body", null, null, null)).build());
        when(plans.findWithTasksById(planId)).thenReturn(Optional.of(prior));
        SpecVersion spec = mock(SpecVersion.class);
        when(spec.getBody()).thenReturn("기획서 본문");
        when(specs.findTopByProjectIdOrderByVersionDesc(projectId)).thenReturn(Optional.of(spec));
        request.startReanalysis(planId);
        request.appendReanalysisInput("메시지를 바꿔 주세요");

        AnalysisInput input = service.loadInput(requestId, null);

        assertThat(input.answer()).isEqualTo("메시지를 바꿔 주세요");
        assertThat(input.priorPlanSummary()).contains("1. [CODE_FEATURE/MEDIUM] 로그인 수정", "diff-body");
        assertThat(input.project().specSummary()).isEqualTo("기획서 본문");
    }

    private AnalysisResult resultWithDiff(String diff) {
        return AnalysisResult.plan(List.of(new AiTask(TaskType.CODE_FEATURE, Risk.MEDIUM, "t", "a", diff, null, null, null)));
    }

    @Test
    void 깨진_diff면_저장하지_않고_분석_실패로_둔다() {
        service.applyResult(requestId, resultWithDiff("--- a/a\n+++ b/a\n@@ -1 +1 @@\nbroken\n"));

        assertThat(request.getStatus()).isEqualTo(RequestStatus.ANALYSIS_FAILED);
        verify(plans, never()).save(any());
    }

    @Test
    void 헌크_줄_수를_고쳐_저장하고_보관한_입력을_비운다() {
        request.appendReanalysisInput("지시");

        service.applyResult(requestId, resultWithDiff("--- a/a\n+++ b/a\n@@ -1,5 +1,5 @@\n-x\n+y\n"));

        ArgumentCaptor<Plan> saved = ArgumentCaptor.forClass(Plan.class);
        verify(plans).save(saved.capture());
        assertThat(saved.getValue().getTasks().getFirst().getContent().diff()).contains("@@ -1,1 +1,1 @@");
        assertThat(request.getStatus()).isEqualTo(RequestStatus.REVIEWING);
        assertThat(request.getReanalysisInput()).isNull();
    }
}
