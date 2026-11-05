package site.siyoung.beforeaibe.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.enums.RequestStatus;
import site.siyoung.beforeaibe.repository.RequestRepository;

import java.util.UUID;

// 에디터 변경 요청은 사용자가 낸 diff가 확정본이므로 AI 분석 없이 커밋 직후 검토 대기(REVIEWING)로 옮긴다.
// 새 트랜잭션이 프록시로 걸리도록 호출은 다른 빈(EditorService)에서 함
@Service
@RequiredArgsConstructor
public class EditorReviewTransition {

    private final RequestRepository requestRepository;
    private final RequestStreamService requestStreamService;

    // afterCommit 시점에는 기존 트랜잭션이 아직 묶여 있을 수 있어 새 트랜잭션으로 실행
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void moveToReviewing(UUID requestId) {
        Request request = requestRepository.findByIdForUpdate(requestId).orElse(null);
        if (request == null || request.getStatus() != RequestStatus.ANALYZING) {
            return;
        }
        request.finishAnalysisWithPlan(request.getCurrentPlanId());
        requestStreamService.publishNodeUpdated(request, RequestStreamService.requestNodeId(requestId),
                request.getStatus().name());
    }
}
