package site.siyoung.beforeaibe.ai;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

// 현재 트랜잭션이 커밋된 뒤에 비동기 분석을 시작하도록 예약한다.
// 저장 전에 분석이 돌거나, 롤백된 요청을 분석하는 것을 막는다.
@Component
@RequiredArgsConstructor
public class RequestAnalysisDispatcher {

    private final RequestAnalysisService analysisService;

    public void dispatchAfterCommit(UUID requestId) {
        dispatchAfterCommit(requestId, null);
    }

    // answer: 재분석 시 요청자가 보낸 추가 답변(최초 분석이면 null)
    public void dispatchAfterCommit(UUID requestId, String answer) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    analysisService.analyzeAsync(requestId, answer);
                }
            });
        } else {
            analysisService.analyzeAsync(requestId, answer);
        }
    }
}
