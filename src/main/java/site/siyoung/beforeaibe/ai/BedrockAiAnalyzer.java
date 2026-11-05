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
import site.siyoung.beforeaibe.entity.value.TaskExplanation;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;
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
                             @Value("${bedrock.max-tokens:8192}") int maxTokens,
                             @Value("${bedrock.temperature:-1}") float temperature) {
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
        InferenceConfiguration.Builder inference = InferenceConfiguration.builder().maxTokens(maxTokens);
        // Claude Sonnet 5.5 등 최신 모델은 temperature를 지원하지 않는다(설정 시 400).
        // temperature가 음수면 전송하지 않고, 0 이상일 때만 보낸다.
        if (temperature >= 0) {
            inference.temperature(temperature);
        }

        ConverseRequest request = ConverseRequest.builder()
                .modelId(modelId)
                .system(SystemContentBlock.fromText(promptBuilder.systemPrompt()))
                .messages(Message.builder()
                        .role(ConversationRole.USER)
                        .content(ContentBlock.fromText(promptBuilder.userPrompt(input)))
                        .build())
                .inferenceConfig(inference.build())
                .build();

        ConverseResponse response = client.converse(request);
        if (response.stopReason() == StopReason.MAX_TOKENS) {
            // 잘린 JSON은 파싱할 수 없으므로 원인이 드러나게 따로 실패시킴
            throw new TruncatedResponseException();
        }
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
                text(t, "diff"), text(t, "command"), text(t, "query"), text(t, "body"),
                parseExplanation(t.get("explanation")));
    }

    // 설명은 표시용 부가 정보라 형식이 틀려도 분석을 실패시키지 않고 null로 둔다.
    // summary가 없으면 통째로 버리고, 나머지는 멀쩡한 항목만 남긴다.
    private TaskExplanation parseExplanation(JsonNode node) {
        try {
            if (node == null || !node.isObject()) {
                return null;
            }
            String summary = textualOrNull(node.get("summary"));
            if (summary == null) {
                return null;
            }
            List<TaskExplanation.Change> changes = new ArrayList<>();
            JsonNode changesNode = node.get("changes");
            if (changesNode != null && changesNode.isArray()) {
                for (JsonNode c : changesNode) {
                    String where = c.isObject() ? textualOrNull(c.get("where")) : null;
                    String what = c.isObject() ? textualOrNull(c.get("what")) : null;
                    if (where != null && what != null) {
                        changes.add(new TaskExplanation.Change(where, what,
                                textualOrNull(c.get("before")), textualOrNull(c.get("after"))));
                    }
                }
            }
            List<String> impact = new ArrayList<>();
            JsonNode impactNode = node.get("impact");
            if (impactNode != null && impactNode.isArray()) {
                for (JsonNode i : impactNode) {
                    String value = textualOrNull(i);
                    if (value != null) {
                        impact.add(value);
                    }
                }
            }
            return new TaskExplanation(summary, changes, impact, textualOrNull(node.get("undo")));
        } catch (RuntimeException e) {
            log.warn("작업 설명 파싱 실패, 설명 없이 진행합니다", e);
            return null;
        }
    }

    // 문자열 노드이고 비어 있지 않을 때만 값을 돌려줌(숫자·객체·배열은 형식 불일치로 봄)
    private String textualOrNull(JsonNode node) {
        return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }

    private static class TruncatedResponseException extends RuntimeException {
        TruncatedResponseException() {
            super("AI 응답이 최대 토큰 수에서 잘렸습니다");
        }
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
