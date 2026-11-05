package site.siyoung.beforeaibe.ai;

import org.junit.jupiter.api.Test;
import site.siyoung.beforeaibe.ai.dto.AnalysisInput;
import site.siyoung.beforeaibe.ai.prompt.PromptBuilder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PromptBuilderTest {

    private final PromptBuilder builder = new PromptBuilder();

    @Test
    void systemPromptDeclaresSchemaAndRules() {
        String system = builder.systemPrompt();
        assertThat(system).contains("JSON");
        assertThat(system).contains("clarification");
        // TaskType enum 값이 스키마에 들어가 있어야 함
        assertThat(system).contains("CODE_FEATURE").contains("DEPLOY").contains("DB");
    }

    @Test
    void userPromptInjectsPromptProjectAndForbiddenRules() {
        AnalysisInput input = new AnalysisInput(
                "로그인 고쳐줘",
                new AnalysisInput.ProjectContext("myproj", "git@repo", "abc123", "기획 요약"),
                List.of("[DB] users - 개인정보 테이블 변경 금지"),
                null, null);

        String user = builder.userPrompt(input);

        assertThat(user).contains("로그인 고쳐줘");
        assertThat(user).contains("myproj").contains("git@repo").contains("abc123");
        assertThat(user).contains("기획 요약");
        assertThat(user).contains("개인정보 테이블 변경 금지");
    }

    @Test
    void userPromptIncludesAnswerOnReanalysis() {
        AnalysisInput input = new AnalysisInput(
                "프롬프트", null, List.of(), "이전 Plan: 작업 2개", "로그인 화면만 대상입니다");

        String user = builder.userPrompt(input);

        assertThat(user).contains("이전 Plan: 작업 2개");
        assertThat(user).contains("로그인 화면만 대상입니다");
    }
}
