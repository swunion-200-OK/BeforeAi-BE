package site.siyoung.beforeaibe.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import site.siyoung.beforeaibe.ai.dto.AnalysisInput;
import site.siyoung.beforeaibe.ai.dto.AnalysisResult;

import java.util.UUID;

// 요청의 AI 분석을 비동기로 돌리는 오케스트레이터.
// 느린 LLM 호출은 트랜잭션 밖에서 하고, DB 작업은 AnalysisPersistenceService(별도 빈)에 위임해
// @Transactional 프록시가 제대로 걸리게 한다.
@Slf4j
@Service
@RequiredArgsConstructor
public class RequestAnalysisService {

    private final AiAnalyzer aiAnalyzer;
    private final AnalysisPersistenceService persistence;

    // 요청 생성·재분석 커밋 후 호출된다. 요청은 이미 ANALYZING 상태.
    // answer: 재분석 시 요청자 추가 답변(최초 분석이면 null)
    @Async(AiAsyncConfig.EXECUTOR)
    public void analyzeAsync(UUID requestId, String answer) {
        try {
            AnalysisInput input = persistence.loadInput(requestId, answer);
            if (input == null) {
                return;
            }
            AnalysisResult result = aiAnalyzer.analyze(input);
            persistence.applyResult(requestId, result);
        } catch (RuntimeException e) {
            log.warn("요청 {} 분석 중 예기치 못한 오류", requestId, e);
            persistence.applyResult(requestId, AnalysisResult.failed("분석 중 오류가 발생했습니다"));
        }
    }
}
