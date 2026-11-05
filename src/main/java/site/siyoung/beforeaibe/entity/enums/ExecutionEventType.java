package site.siyoung.beforeaibe.entity.enums;

// 웹쉘 콜백 이벤트 종류 (POST /internal/executions/{executionId}/events). 원문 명세에 목록이 없어 웹쉘과 합의할 계약으로 정함.
// ExecutionEvent.type 컬럼은 받은 값을 그대로 남기도록 문자열로 둠
public enum ExecutionEventType {
    // taskId 필수. 작업 APPROVED → RUNNING, 진행 트리에 STEP 노드 추가
    TASK_STARTED,
    // taskId 필수. 작업 → SUCCEEDED
    TASK_SUCCEEDED,
    // taskId 필수. 작업 → FAILED
    TASK_FAILED,
    // data.sequence(정수)·data.message(문자열). 실행 로그 한 줄
    LOG,
    // data.status(COMPLETED|PARTIALLY_COMPLETED|FAILED). 실행·요청의 최종 상태 확정
    EXECUTION_FINISHED,
    // 검증 실패·정책 불일치 경보. 이벤트만 저장함
    ALERT
}
