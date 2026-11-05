package site.siyoung.beforeaibe.service;

import org.junit.jupiter.api.Test;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.enums.RequestSource;
import site.siyoung.beforeaibe.entity.enums.RequestStatus;
import site.siyoung.beforeaibe.repository.RequestRepository;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class EditorReviewTransitionTest {

    private final RequestRepository requestRepository = mock(RequestRepository.class);
    private final RequestStreamService streamService = mock(RequestStreamService.class);
    private final EditorReviewTransition transition = new EditorReviewTransition(requestRepository, streamService);

    private Request analyzingRequest(UUID planId) {
        Request request = Request.builder()
                .projectId(UUID.randomUUID())
                .requesterId(UUID.randomUUID())
                .prompt("수정")
                .source(RequestSource.EDITOR)
                .status(RequestStatus.ANALYZING)
                .analysisAttempts(1)
                .build();
        request.attachPlan(planId);
        return request;
    }

    @Test
    void 동기화가_없으면_바로_REVIEWING으로_전이하고_Plan을_유지한다() {
        UUID id = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        Request request = analyzingRequest(planId);
        given(requestRepository.findByIdForUpdate(id)).willReturn(Optional.of(request));

        transition.moveToReviewing(id);

        assertThat(request.getStatus()).isEqualTo(RequestStatus.REVIEWING);
        assertThat(request.getCurrentPlanId()).isEqualTo(planId);
        verify(streamService).publishNodeUpdated(eq(request), any(), eq("REVIEWING"));
    }

    @Test
    void ANALYZING이_아니면_전이하지_않는다() {
        UUID id = UUID.randomUUID();
        Request request = analyzingRequest(UUID.randomUUID());
        request.finishAnalysisWithPlan(request.getCurrentPlanId());
        request.submit();
        given(requestRepository.findByIdForUpdate(id)).willReturn(Optional.of(request));

        transition.moveToReviewing(id);

        assertThat(request.getStatus()).isEqualTo(RequestStatus.PENDING_APPROVAL);
        verify(streamService, never()).publishNodeUpdated(any(), any(), any());
    }
}
