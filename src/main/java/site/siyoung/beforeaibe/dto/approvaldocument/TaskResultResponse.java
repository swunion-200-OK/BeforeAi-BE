package site.siyoung.beforeaibe.dto.approvaldocument;

import site.siyoung.beforeaibe.entity.Task;
import site.siyoung.beforeaibe.entity.enums.TaskStatus;

import java.util.UUID;

// 실행 결과는 작업별 최종 상태만 담음
public record TaskResultResponse(
        UUID taskId,
        int order,
        String title,
        TaskStatus status
) {

    public static TaskResultResponse from(Task task) {
        return new TaskResultResponse(task.getId(), task.getOrder(), task.getTitle(), task.getStatus());
    }
}
