package site.siyoung.beforeaibe.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import site.siyoung.beforeaibe.entity.Plan;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.Task;
import site.siyoung.beforeaibe.entity.enums.RequestStatus;
import site.siyoung.beforeaibe.entity.enums.Risk;
import site.siyoung.beforeaibe.entity.enums.TaskStatus;
import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.exception.ErrorCode;
import site.siyoung.beforeaibe.repository.ApprovalRepository;
import site.siyoung.beforeaibe.repository.ExecutionRepository;
import site.siyoung.beforeaibe.repository.RequestRepository;
import site.siyoung.beforeaibe.repository.SigningKeyRepository;
import site.siyoung.beforeaibe.webshell.WebshellClient;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// 승인 커밋 이후 다른 트랜잭션이 변경한 상태를 두 번째 행 잠금에서 읽는 상황을 재현함
class ApprovalTransactionServiceTest {

    private final UUID requestId = UUID.randomUUID();
    private final UUID approvedPlanId = UUID.randomUUID();
    private final RequestService requestService = mock(RequestService.class);
    private final RequestRepository requestRepository = mock(RequestRepository.class);
    private final ApprovalRepository approvalRepository = mock(ApprovalRepository.class);
    private final ExecutionRepository executionRepository = mock(ExecutionRepository.class);
    private final PlanHashService planHashService = mock(PlanHashService.class);
    private final RequestStreamService requestStreamService = mock(RequestStreamService.class);
    private final WebshellClient webshellClient = mock(WebshellClient.class);
    private final ApprovalTransactionService service = new ApprovalTransactionService(
            requestService, requestRepository, approvalRepository, mock(SigningKeyRepository.class),
            executionRepository, planHashService, requestStreamService, webshellClient, new ApprovalPolicy());

    @Test
    void 승인_커밋_후_취소된_요청은_실행하지_않는다() {
        Request request = lockedRequest(RequestStatus.CANCELED, approvedPlanId);

        assertExecutionBlocked(request);

        verifyNoInteractions(requestService);
    }

    @Test
    void 승인한_Plan이_교체되었으면_실행하지_않는다() {
        Request request = lockedRequest(RequestStatus.PENDING_APPROVAL, UUID.randomUUID());

        assertExecutionBlocked(request);

        verifyNoInteractions(requestService);
    }

    @Test
    void 현재_Plan에_미승인_작업이_있으면_실행하지_않는다() {
        Request request = lockedRequest(RequestStatus.PENDING_APPROVAL, approvedPlanId);
        Plan plan = Plan.builder().request(request).build();
        ReflectionTestUtils.setField(plan, "id", approvedPlanId);
        plan.addTask(Task.builder().risk(Risk.MEDIUM).status(TaskStatus.APPROVED).build());
        plan.addTask(Task.builder().risk(Risk.MEDIUM).status(TaskStatus.PENDING).build());
        when(requestService.getCurrentPlan(request)).thenReturn(plan);

        assertExecutionBlocked(request);

        assertThat(plan.getTasks()).extracting(Task::getStatus)
                .containsExactly(TaskStatus.APPROVED, TaskStatus.PENDING);
        assertThat(plan.getExecutorSignature()).isNull();
    }

    @Test
    void 같은_Plan이_이미_실행_중이면_중복_실행을_요청하지_않는다() {
        Request request = lockedRequest(RequestStatus.RUNNING, approvedPlanId);

        service.requestExecution(requestId, approvedPlanId);

        assertThat(request.getStatus()).isEqualTo(RequestStatus.RUNNING);
        verifyNoInteractions(requestService, approvalRepository, executionRepository,
                planHashService, requestStreamService, webshellClient);
    }

    private Request lockedRequest(RequestStatus status, UUID currentPlanId) {
        Request request = Request.builder().status(status).currentPlanId(currentPlanId).build();
        ReflectionTestUtils.setField(request, "id", requestId);
        when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
        return request;
    }

    private void assertExecutionBlocked(Request request) {
        RequestStatus originalStatus = request.getStatus();
        UUID originalPlanId = request.getCurrentPlanId();

        assertThatThrownBy(() -> service.requestExecution(requestId, approvedPlanId))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE));

        assertThat(request.getStatus()).isEqualTo(originalStatus);
        assertThat(request.getCurrentPlanId()).isEqualTo(originalPlanId);
        assertThat(request.getProgressVersion()).isZero();
        verifyNoInteractions(approvalRepository, executionRepository, planHashService,
                requestStreamService, webshellClient);
    }
}
