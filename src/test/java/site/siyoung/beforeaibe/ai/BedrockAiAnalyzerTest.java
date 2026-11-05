package site.siyoung.beforeaibe.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import site.siyoung.beforeaibe.ai.dto.AnalysisInput;
import site.siyoung.beforeaibe.ai.dto.AnalysisResult;
import site.siyoung.beforeaibe.ai.prompt.PromptBuilder;
import site.siyoung.beforeaibe.entity.enums.Risk;
import site.siyoung.beforeaibe.entity.enums.TaskType;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.Message;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Bedrock 클라이언트를 모킹해 응답 JSON 파싱·분기만 검증(실제 AWS 호출 없음).
class BedrockAiAnalyzerTest {

    private final PromptBuilder promptBuilder = new PromptBuilder();
    private final ObjectMapper objectMapper = new ObjectMapper();

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
        return new BedrockAiAnalyzer(client, promptBuilder, objectMapper,
                "test-model", 1024, 0.2f);
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
}
