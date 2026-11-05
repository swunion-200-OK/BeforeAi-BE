package site.siyoung.beforeaibe.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import site.siyoung.beforeaibe.ai.dto.AiTask;
import site.siyoung.beforeaibe.ai.dto.AnalysisInput;
import site.siyoung.beforeaibe.ai.dto.AnalysisResult;
import site.siyoung.beforeaibe.ai.prompt.PromptBuilder;
import site.siyoung.beforeaibe.entity.enums.Risk;
import site.siyoung.beforeaibe.entity.enums.TaskType;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;

import java.util.ArrayList;
import java.util.List;

// bedrock.enabled=true일 때 쓰는 Claude Sonnet 5.5 기반 분석기.
// Converse API로 호출하고, 응답 텍스트(JSON)를 파싱해 AnalysisResult로 만든다.
@Slf4j
@Component
@ConditionalOnProperty(name = "bedrock.enabled", havingValue = "true")
public class BedrockAiAnalyzer implements AiAnalyzer {

    private final BedrockRuntimeClient client;
    private final PromptBuilder promptBuilder;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String modelId;
    private final int maxTokens;
    private final float temperature;

    public BedrockAiAnalyzer(BedrockRuntimeClient client,
                             PromptBuilder promptBuilder,
                             @Value("${bedrock.model-id}") String modelId,
                             @Value("${bedrock.max-tokens:4096}") int maxTokens,
                             @Value("${bedrock.temperature:0.2}") float temperature) {
        this.client = client;
        this.promptBuilder = promptBuilder;
        this.modelId = modelId;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
    }

    @Override
    public AnalysisResult analyze(AnalysisInput input) {
        try {
            String text = invoke(input);
            return parse(text);
        } catch (RuntimeException e) {
            log.warn("Bedrock 분석 실패", e);
            return AnalysisResult.failed("AI 분석 호출에 실패했습니다: " + e.getClass().getSimpleName());
        }
    }

    private String invoke(AnalysisInput input) {
        ConverseRequest request = ConverseRequest.builder()
                .modelId(modelId)
                .system(SystemContentBlock.fromText(promptBuilder.systemPrompt()))
                .messages(Message.builder()
                        .role(ConversationRole.USER)
                        .content(ContentBlock.fromText(promptBuilder.userPrompt(input)))
                        .build())
                .inferenceConfig(InferenceConfiguration.builder()
                        .maxTokens(maxTokens)
                        .temperature(temperature)
                        .build())
                .build();

        ConverseResponse response = client.converse(request);
        return response.output().message().content().stream()
                .filter(block -> block.type() == ContentBlock.Type.TEXT)
                .map(ContentBlock::text)
                .reduce("", String::concat);
    }

    // 모델이 코드펜스를 붙여도 JSON 본문만 뽑아 파싱한다.
    private AnalysisResult parse(String text) {
        String json = extractJson(text);
        if (json == null) {
            return AnalysisResult.failed("AI 응답에서 JSON을 찾지 못했습니다");
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode clarification = root.get("clarification");
            if (clarification != null && !clarification.isNull() && !clarification.asText().isBlank()) {
                return AnalysisResult.clarify(clarification.asText());
            }
            JsonNode tasksNode = root.get("tasks");
            if (tasksNode == null || !tasksNode.isArray() || tasksNode.isEmpty()) {
                return AnalysisResult.failed("AI가 생성한 작업이 없습니다");
            }
            List<AiTask> tasks = new ArrayList<>();
            for (JsonNode t : tasksNode) {
                tasks.add(toTask(t));
            }
            return AnalysisResult.plan(tasks);
        } catch (Exception e) {
            log.warn("AI 응답 JSON 파싱 실패: {}", json, e);
            return AnalysisResult.failed("AI 응답 형식이 올바르지 않습니다");
        }
    }

    private AiTask toTask(JsonNode t) {
        TaskType type = TaskType.valueOf(text(t, "type"));
        Risk risk = parseRisk(text(t, "risk"));
        return new AiTask(type, risk, text(t, "title"), text(t, "target"),
                text(t, "diff"), text(t, "command"), text(t, "query"), text(t, "body"));
    }

    // 모델이 FORBIDDEN 등 범위를 벗어난 값을 줄 수 있어 LOW/MEDIUM/HIGH만 허용, 그 외는 MEDIUM으로 보정
    private Risk parseRisk(String value) {
        if (value == null) {
            return Risk.MEDIUM;
        }
        try {
            Risk risk = Risk.valueOf(value.trim().toUpperCase());
            return risk == Risk.FORBIDDEN ? Risk.MEDIUM : risk;
        } catch (IllegalArgumentException e) {
            return Risk.MEDIUM;
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private String extractJson(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }
}
