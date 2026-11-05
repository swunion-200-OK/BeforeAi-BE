package site.siyoung.beforeaibe.ai.dto;

import site.siyoung.beforeaibe.entity.enums.Risk;
import site.siyoung.beforeaibe.entity.enums.TaskType;
import site.siyoung.beforeaibe.entity.value.TaskExplanation;

// AI가 생성한 작업 하나. Task 엔티티로 매핑되기 전의 분석 결과 표현.
// content 는 type 에 맞는 필드 하나만 채워진다(diff/command/query/body). explanation 은 없을 수 있다(null).
public record AiTask(
        TaskType type,
        Risk risk,
        String title,
        String target,
        String diff,
        String command,
        String query,
        String body,
        TaskExplanation explanation
) {
}
