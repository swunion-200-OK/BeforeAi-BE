package site.siyoung.beforeaibe.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import site.siyoung.beforeaibe.dto.editor.EditorChangeRequest;
import site.siyoung.beforeaibe.dto.editor.EditorChangeResponse;
import site.siyoung.beforeaibe.dto.editor.FileChange;
import site.siyoung.beforeaibe.dto.editor.FileContentResponse;
import site.siyoung.beforeaibe.dto.editor.FileListResponse;
import site.siyoung.beforeaibe.entity.Plan;
import site.siyoung.beforeaibe.entity.Project;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.Task;
import site.siyoung.beforeaibe.entity.enums.PlanKind;
import site.siyoung.beforeaibe.entity.enums.RequestSource;
import site.siyoung.beforeaibe.entity.enums.RequestStatus;
import site.siyoung.beforeaibe.entity.enums.Risk;
import site.siyoung.beforeaibe.entity.enums.TaskStatus;
import site.siyoung.beforeaibe.entity.enums.TaskType;
import site.siyoung.beforeaibe.entity.value.TaskContent;
import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.exception.ErrorCode;
import site.siyoung.beforeaibe.repository.PlanRepository;
import site.siyoung.beforeaibe.repository.RequestRepository;
import site.siyoung.beforeaibe.webshell.WebshellClient;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

// 웹 코드 에디터 (API 명세서 3.8, CORE-05): 저장소 읽기 조회(#1,#2)와 에디터 변경을 승인 대상 요청으로 저장(#3)
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EditorService {

    // 분석이 처음 시작되므로 시도 횟수는 1부터 셈 (RequestService와 동일)
    private static final int FIRST_ANALYSIS_ATTEMPT = 1;
    // 에디터 변경 Task의 Plan 내 유일한 순번
    private static final int SINGLE_TASK_ORDER = 0;
    // AI 위험도 분류(Bedrock) 연동 전 기본값. 분류 hook 연동 시 교체 (API 명세서 3.8, implplan-3.8 §3)
    private static final Risk UNCLASSIFIED_RISK = Risk.MEDIUM;

    private final ProjectService projectService;
    private final WebshellClient webshellClient;
    private final RequestRepository requestRepository;
    private final PlanRepository planRepository;
    private final ApproverResolver approverResolver;
    private final EditorReviewTransition editorReviewTransition;

    // #1 GET /projects/{projectId}/files — 저장소 디렉터리 목록 + 기준 커밋 (읽기 전용)
    public FileListResponse listFiles(UUID projectId, String path, String ref) {
        Project project = projectService.getProject(projectId);
        return FileListResponse.from(webshellClient.listFiles(project.getRepository(), path, ref));
    }

    // #2 GET /projects/{projectId}/files/content — 파일 내용 (읽기 전용)
    public FileContentResponse readFile(UUID projectId, String path, String ref) {
        Project project = projectService.getProject(projectId);
        return FileContentResponse.from(webshellClient.readFile(project.getRepository(), path, ref));
    }

    // #3 POST /projects/{projectId}/editor/changes — 에디터 변경을 source=EDITOR 요청으로 저장.
    // 조회 당시 기준 커밋이 바뀌었으면 409 EDIT_CONFLICT. 코드 수정 작업 1개짜리 Plan을 만들어 바로 연결함
    @Transactional
    public EditorChangeResponse createChange(UUID userId, UUID projectId, EditorChangeRequest request) {
        Project project = projectService.getProject(projectId);

        // 에디터를 연 뒤 저장소가 바뀌었는지 검사. 현재 기준 커밋은 ref를 따로 받지 않으므로 기본 브랜치로 조회
        String currentCommit = webshellClient.currentCommit(project.getRepository(), null);
        if (!request.baseCommit().equals(currentCommit)) {
            throw new ApiException(ErrorCode.EDIT_CONFLICT);
        }

        Request saved = requestRepository.save(Request.builder()
                .projectId(projectId)
                .requesterId(userId)
                .prompt(request.description())
                .source(RequestSource.EDITOR)
                .status(RequestStatus.ANALYZING)
                .analysisAttempts(FIRST_ANALYSIS_ATTEMPT)
                .build());

        Plan plan = Plan.builder()
                .request(saved)
                .kind(PlanKind.NORMAL)
                .repository(project.getRepository())
                .baseCommit(request.baseCommit())
                .build();
        plan.addTask(buildCodeTask(request, project, userId));
        planRepository.save(plan);

        saved.attachPlan(plan.getId());
        // 응답은 ANALYZING 그대로 두고, 커밋 뒤 AI 분석 없이 REVIEWING으로 옮겨 제출·승인 흐름에 태움
        UUID requestId = saved.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                editorReviewTransition.moveToReviewing(requestId);
            }
        });
        return EditorChangeResponse.from(saved);
    }

    // 파일별 unified diff를 하나로 합쳐 코드 수정 작업 1개로 만듦. 여러 파일이면 target은 대표 경로 표기.
    // AI가 만든 작업이 아니므로 explanation은 null
    private Task buildCodeTask(EditorChangeRequest request, Project project, UUID userId) {
        List<FileChange> files = request.files();
        String combinedDiff = files.stream().map(FileChange::patch).collect(Collectors.joining("\n"));
        return Task.builder()
                .order(SINGLE_TASK_ORDER)
                .type(TaskType.CODE_MINOR)
                .risk(UNCLASSIFIED_RISK)
                .title(request.description())
                .target(describeTarget(files))
                .content(new TaskContent(combinedDiff, null, null, null))
                .relatedDepartments(List.of())
                .requiredApprovers(approverResolver.resolve(TaskType.CODE_MINOR, UNCLASSIFIED_RISK, project, userId))
                .status(TaskStatus.PENDING)
                .build();
    }

    // 단일 파일이면 그 경로, 여러 파일이면 "첫 경로 외 N건"
    private String describeTarget(List<FileChange> files) {
        String first = files.getFirst().path();
        return files.size() == 1 ? first : first + " 외 " + (files.size() - 1) + "건";
    }
}
