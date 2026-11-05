package site.siyoung.beforeaibe.ai;

import site.siyoung.beforeaibe.ai.dto.AnalysisInput;
import site.siyoung.beforeaibe.ai.dto.AnalysisResult;

// 프롬프트를 작업 목록(Plan)으로 분해하는 AI 분석기 경계.
// 구현체는 Bedrock(활성) 또는 Noop(비활성)이며, 다른 LLM으로 교체해도 이 경계만 유지하면 된다.
public interface AiAnalyzer {

    AnalysisResult analyze(AnalysisInput input);
}
