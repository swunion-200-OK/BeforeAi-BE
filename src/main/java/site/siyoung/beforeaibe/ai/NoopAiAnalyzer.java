package site.siyoung.beforeaibe.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import site.siyoung.beforeaibe.ai.dto.AnalysisInput;
import site.siyoung.beforeaibe.ai.dto.AnalysisResult;

// bedrock.enabled=false(기본)일 때 쓰는 비활성 분석기.
// 분석을 수행하지 않고 "분석기 미연동"으로 실패 처리한다. 요청은 ANALYSIS_FAILED가 되어 기존처럼 수동 편집/재시도 가능.
@Slf4j
@Component
@ConditionalOnProperty(name = "bedrock.enabled", havingValue = "false", matchIfMissing = true)
public class NoopAiAnalyzer implements AiAnalyzer {

    public NoopAiAnalyzer() {
        log.info("AI 분석기 비활성(bedrock.enabled=false). 요청은 수동 Plan 편집으로만 진행됩니다.");
    }

    @Override
    public AnalysisResult analyze(AnalysisInput input) {
        return AnalysisResult.failed("AI 분석기가 비활성화되어 있습니다");
    }
}
