package site.siyoung.beforeaibe.ai;

import org.junit.jupiter.api.Test;
import site.siyoung.beforeaibe.ai.dto.AnalysisInput;
import site.siyoung.beforeaibe.ai.dto.AnalysisResult;
import site.siyoung.beforeaibe.ai.prompt.PromptBuilder;
import site.siyoung.beforeaibe.entity.enums.Risk;
import site.siyoung.beforeaibe.entity.enums.TaskType;
import site.siyoung.beforeaibe.entity.value.TaskExplanation;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Bedrock 클라이언트를 모킹해 응답 JSON 파싱·분기만 검증(실제 AWS 호출 없음).
class BedrockAiAnalyzerTest {

    private final PromptBuilder promptBuilder = new PromptBuilder();

    private BedrockAiAnalyzer analyzerReturning(String modelText) {
        BedrockRuntimeClient client = mock(BedrockRuntimeClient.class);
        ConverseResponse response = ConverseResponse.builder()
                .output(ConverseOutput.builder()
                        .message(Message.builder()
                                .role(ConversationRole.ASSISTANT)
                                .content(ContentBlock.fromText(modelText))
                                .build())
                        .build())
                .build();
        when(client.converse(any(ConverseRequest.class))).thenReturn(response);
        return new BedrockAiAnalyzer(client, promptBuilder, "test-model", 1024, 0.2f);
    }

    private AnalysisInput input() {
        return AnalysisInput.of("로그인 에러 메시지를 바꿔줘",
                new AnalysisInput.ProjectContext("proj", "git@x", "HEAD", null), List.of());
    }

    @Test
    void parsesTasksFromJson() {
        String json = """
                {"clarification": null, "tasks": [
                  {"type":"CODE_MINOR","risk":"LOW","title":"메시지 수정","target":"Login.java",
                   "diff":"- a\\n+ b","command":null,"query":null,"body":null}
                ]}""";
        AnalysisResult result = analyzerReturning(json).analyze(input());

        assertThat(result.isFailed()).isFalse();
        assertThat(result.isClarification()).isFalse();
        assertThat(result.tasks()).hasSize(1);
        assertThat(result.tasks().getFirst().type()).isEqualTo(TaskType.CODE_MINOR);
        assertThat(result.tasks().getFirst().risk()).isEqualTo(Risk.LOW);
        assertThat(result.tasks().getFirst().diff()).isEqualTo("- a\n+ b");
    }

    @Test
    void handlesCodeFenceWrappedJson() {
        String fenced = "```json\n{\"clarification\":null,\"tasks\":[" +
                "{\"type\":\"DEPLOY\",\"risk\":\"HIGH\",\"title\":\"배포\",\"target\":\"staging\"," +
                "\"diff\":null,\"command\":\"deploy.sh\",\"query\":null,\"body\":null}]}\n```";
        AnalysisResult result = analyzerReturning(fenced).analyze(input());

        assertThat(result.tasks()).hasSize(1);
        assertThat(result.tasks().getFirst().type()).isEqualTo(TaskType.DEPLOY);
        assertThat(result.tasks().getFirst().command()).isEqualTo("deploy.sh");
    }

    @Test
    void returnsClarificationWhenPresent() {
        String json = "{\"clarification\":\"어느 화면의 로그인인가요?\",\"tasks\":[]}";
        AnalysisResult result = analyzerReturning(json).analyze(input());

        assertThat(result.isClarification()).isTrue();
        assertThat(result.clarification()).contains("어느 화면");
    }

    @Test
    void failsOnEmptyTasks() {
        AnalysisResult result = analyzerReturning("{\"clarification\":null,\"tasks\":[]}").analyze(input());
        assertThat(result.isFailed()).isTrue();
    }

    @Test
    void failsOnNonJson() {
        AnalysisResult result = analyzerReturning("죄송합니다 JSON이 아닙니다").analyze(input());
        assertThat(result.isFailed()).isTrue();
    }

    @Test
    void forbiddenRiskFromModelIsDowngradedToMedium() {
        // 모델이 FORBIDDEN을 줘도 분석기는 MEDIUM으로 보정(FORBIDDEN 판정은 서버 몫)
        String json = "{\"clarification\":null,\"tasks\":[" +
                "{\"type\":\"DB\",\"risk\":\"FORBIDDEN\",\"title\":\"삭제\",\"target\":\"users\"," +
                "\"diff\":null,\"command\":null,\"query\":\"DROP TABLE users\",\"body\":null}]}";
        AnalysisResult result = analyzerReturning(json).analyze(input());

        assertThat(result.tasks().getFirst().risk()).isEqualTo(Risk.MEDIUM);
    }

    private static String taskJson(String explanation) {
        return "{\"clarification\":null,\"tasks\":[{\"type\":\"DOC\",\"risk\":\"LOW\",\"title\":\"t\",\"target\":\"a.md\","
                + "\"diff\":null,\"command\":null,\"query\":null,\"body\":\"본문\"" + explanation + "}]}";
    }

    @Test
    void explanation을_파싱한다() {
        String json = taskJson(",\"explanation\":{\"summary\":\"문서를 만든다\","
                + "\"changes\":[{\"where\":\"a.md\",\"what\":\"새 문서\",\"after\":\"본문\"}],"
                + "\"impact\":[\"독자\"],\"undo\":\"삭제\"}");

        AnalysisResult result = analyzerReturning(json).analyze(input());

        assertThat(result.isFailed()).isFalse();
        TaskExplanation explanation = result.tasks().getFirst().explanation();
        assertThat(explanation.summary()).isEqualTo("문서를 만든다");
        assertThat(explanation.changes()).containsExactly(new TaskExplanation.Change("a.md", "새 문서", null, "본문"));
        assertThat(explanation.impact()).containsExactly("독자");
        assertThat(explanation.undo()).isEqualTo("삭제");
    }

    @Test
    void explanation이_없거나_형식이_틀려도_분석은_성공하고_설명만_null이다() {
        List<String> broken = List.of(
                "", ",\"explanation\":null", ",\"explanation\":\"문자열\"", ",\"explanation\":[1,2]",
                ",\"explanation\":{}", ",\"explanation\":{\"summary\":123}", ",\"explanation\":{\"summary\":{\"a\":1}}",
                ",\"explanation\":{\"summary\":\" \"}");
        for (String explanation : broken) {
            AnalysisResult result = analyzerReturning(taskJson(explanation)).analyze(input());

            assertThat(result.isFailed()).as(explanation).isFalse();
            assertThat(result.tasks()).as(explanation).hasSize(1);
            assertThat(result.tasks().getFirst().explanation()).as(explanation).isNull();
        }
    }

    @Test
    void explanation의_일부_필드가_틀리면_멀쩡한_필드만_남긴다() {
        String json = taskJson(",\"explanation\":{\"summary\":\"요약\","
                + "\"changes\":[\"문자열\",{\"where\":\"a\"},{\"where\":\"b\",\"what\":\"c\",\"before\":5}],"
                + "\"impact\":\"배열 아님\",\"undo\":[\"x\"]}");

        AnalysisResult result = analyzerReturning(json).analyze(input());

        assertThat(result.isFailed()).isFalse();
        TaskExplanation explanation = result.tasks().getFirst().explanation();
        assertThat(explanation.summary()).isEqualTo("요약");
        assertThat(explanation.changes()).containsExactly(new TaskExplanation.Change("b", "c", null, null));
        assertThat(explanation.impact()).isEmpty();
        assertThat(explanation.undo()).isNull();
    }

    @Test
    void 응답이_최대_토큰에서_잘리면_분석_실패로_돌린다() {
        BedrockRuntimeClient client = mock(BedrockRuntimeClient.class);
        when(client.converse(any(ConverseRequest.class))).thenReturn(ConverseResponse.builder()
                .stopReason(StopReason.MAX_TOKENS)
                .output(ConverseOutput.builder().message(Message.builder().role(ConversationRole.ASSISTANT)
                        .content(ContentBlock.fromText("{\"clarification\":null,\"tasks\":[{\"type\":\"DOC\"}]}")).build()).build())
                .build());

        AnalysisResult result = new BedrockAiAnalyzer(client, promptBuilder, "m", 1024, 0.2f).analyze(input());

        assertThat(result.isFailed()).isTrue();
    }
}
