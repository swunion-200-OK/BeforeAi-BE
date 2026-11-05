package site.siyoung.beforeaibe.ai.dto;

import java.util.List;

// AI 분석기 입력. 프롬프트와 프로젝트 컨텍스트, 금지 규칙, 재분석 시 추가 입력을 담는다.
public record AnalysisInput(
        String prompt,
        ProjectContext project,
        List<String> forbiddenRules,
        // 재분석일 때만 채워짐: 이전 Plan 요약, 요청자 답변/수정 지시
        String priorPlanSummary,
        String answer
) {

    // 분석기에 넘기는 프로젝트 요약. 저장소/기준 커밋은 Plan 생성에 쓰인다.
    public record ProjectContext(
            String name,
            String repository,
            String baseCommit,
            String specSummary
    ) {
    }

    public static AnalysisInput of(String prompt, ProjectContext project, List<String> forbiddenRules) {
        return new AnalysisInput(prompt, project, forbiddenRules, null, null);
    }
}
