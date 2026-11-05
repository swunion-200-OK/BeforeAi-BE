package site.siyoung.beforeaibe.ai.dto;

import java.util.List;

// AI 분석 결과. 셋 중 하나의 상태를 나타낸다:
//  - tasks 가 있으면 Plan 생성 (REVIEWING)
//  - clarification 이 있으면 요청자에게 되묻기 (REVIEWING + clarification)
//  - failedReason 이 있으면 분석 실패 (ANALYSIS_FAILED)
public record AnalysisResult(
        List<AiTask> tasks,
        String clarification,
        String failedReason
) {

    public static AnalysisResult plan(List<AiTask> tasks) {
        return new AnalysisResult(tasks, null, null);
    }

    public static AnalysisResult clarify(String clarification) {
        return new AnalysisResult(List.of(), clarification, null);
    }

    public static AnalysisResult failed(String reason) {
        return new AnalysisResult(List.of(), null, reason);
    }

    public boolean isClarification() {
        return clarification != null && !clarification.isBlank();
    }

    public boolean isFailed() {
        return failedReason != null && !failedReason.isBlank();
    }
}
