package site.siyoung.beforeaibe.ai;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import site.siyoung.beforeaibe.ai.dto.AiTask;
import site.siyoung.beforeaibe.ai.dto.AnalysisInput;
import site.siyoung.beforeaibe.ai.dto.AnalysisResult;
import site.siyoung.beforeaibe.entity.enums.Risk;
import site.siyoung.beforeaibe.entity.enums.TaskType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class DemoAnalysisCacheTest {

    private static final String LOW = "docs/incident-guide.md에 장애 대응 절차를 새로 작성해줘";
    private static final String HIGH =
            "docs/release-note.md에 v1.2.0 릴리스 노트를 새로 만들고 ./deploy.sh production으로 운영 서버에 배포해줘";

    private static DemoAnalysisCache cache(boolean enabled) {
        return new DemoAnalysisCache(enabled, 0, new ClassPathResource("ai/demo-analysis-cache.json"));
    }

    @Test
    void 낮은_위험_문장은_질문_없이_DOC_LOW_Plan을_돌려준다() {
        AnalysisResult result = cache(true).find(LOW).orElseThrow();

        assertThat(result.clarification()).isNull();
        assertThat(result.failedReason()).isNull();
        assertThat(result.tasks()).extracting(AiTask::type, AiTask::risk)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(TaskType.DOC, Risk.LOW));
        assertThat(result.tasks().getFirst().target()).isEqualTo("docs/incident-guide.md");
        assertThat(result.tasks().getFirst().body()).startsWith("# 장애 대응 가이드");
    }

    @Test
    void 높은_위험_문장은_질문_없이_DOC와_DEPLOY_HIGH_Plan을_돌려준다() {
        AnalysisResult result = cache(true).find(HIGH).orElseThrow();

        assertThat(result.clarification()).isNull();
        assertThat(result.tasks()).extracting(AiTask::type, AiTask::risk).containsExactly(
                org.assertj.core.groups.Tuple.tuple(TaskType.DOC, Risk.LOW),
                org.assertj.core.groups.Tuple.tuple(TaskType.DEPLOY, Risk.HIGH));
        assertThat(result.tasks().getLast().command()).isEqualTo("./deploy.sh production");
    }

    @Test
    void 앞뒤_공백은_무시한다() {
        assertThat(cache(true).find("  " + LOW + "\n")).isPresent();
    }

    @Test
    void 프롬프트가_다르면_캐시를_쓰지_않는다() {
        assertThat(cache(true).find(LOW + " 그리고 배포도 해줘")).isEmpty();
        assertThat(cache(true).find(null)).isEmpty();
    }

    @Test
    void 꺼져_있으면_같은_프롬프트도_캐시를_쓰지_않는다() {
        assertThat(cache(false).find(LOW)).isEmpty();
    }

    @Test
    void 캐시_파일이_깨져도_기동을_막지_않고_캐시만_비운다() {
        DemoAnalysisCache broken = new DemoAnalysisCache(true, 0, new ByteArrayResource("{깨진 파일".getBytes()));

        assertThat(broken.find(LOW)).isEmpty();
    }

    private static AnalysisInput input(String prompt) {
        return AnalysisInput.of(prompt, new AnalysisInput.ProjectContext("프로젝트", "git@internal:a.git", "HEAD", null),
                List.of());
    }

    @Test
    void 캐시가_있으면_AI를_호출하지_않고_그_결과를_저장한다() {
        UUID requestId = UUID.randomUUID();
        AiAnalyzer analyzer = mock(AiAnalyzer.class);
        DemoAnalysisCache cache = mock(DemoAnalysisCache.class);
        AnalysisPersistenceService persistence = mock(AnalysisPersistenceService.class);
        AnalysisResult cached = AnalysisResult.plan(List.of());
        given(persistence.loadInput(requestId, null)).willReturn(input(LOW));
        given(cache.find(LOW)).willReturn(Optional.of(cached));

        new RequestAnalysisService(analyzer, cache, persistence).analyzeAsync(requestId, null);

        verify(analyzer, never()).analyze(any());
        verify(persistence).applyResult(requestId, cached);
    }

    @Test
    void 캐시에_없으면_AI로_분석한다() {
        UUID requestId = UUID.randomUUID();
        AiAnalyzer analyzer = mock(AiAnalyzer.class);
        DemoAnalysisCache cache = mock(DemoAnalysisCache.class);
        AnalysisPersistenceService persistence = mock(AnalysisPersistenceService.class);
        AnalysisResult live = AnalysisResult.plan(List.of());
        AnalysisInput input = input("다른 요청");
        given(persistence.loadInput(requestId, null)).willReturn(input);
        given(cache.find("다른 요청")).willReturn(Optional.empty());
        given(analyzer.analyze(input)).willReturn(live);

        new RequestAnalysisService(analyzer, cache, persistence).analyzeAsync(requestId, null);

        verify(persistence).applyResult(requestId, live);
    }

    @Test
    void 재분석은_같은_프롬프트여도_캐시를_쓰지_않는다() {
        UUID requestId = UUID.randomUUID();
        AiAnalyzer analyzer = mock(AiAnalyzer.class);
        DemoAnalysisCache cache = mock(DemoAnalysisCache.class);
        AnalysisPersistenceService persistence = mock(AnalysisPersistenceService.class);
        AnalysisResult live = AnalysisResult.plan(List.of());
        AnalysisInput input = input(LOW);
        given(persistence.loadInput(requestId, "제목을 바꿔줘")).willReturn(input);
        given(analyzer.analyze(input)).willReturn(live);

        new RequestAnalysisService(analyzer, cache, persistence).analyzeAsync(requestId, "제목을 바꿔줘");

        verify(cache, never()).find(any());
        verify(persistence).applyResult(eq(requestId), eq(live));
    }
}
