package site.siyoung.beforeaibe.entity.enums;

// 웹쉘 실행 이벤트 콜백의 종류. 계약은 .docs/webshell-callback-events.md.
// ExecutionEvent.type 컬럼은 계약 확정 전이라 문자열로 두고, 받을 때 이 enum으로 검증함
public enum ExecutionEventType {
    TASK_STARTED,
    TASK_SUCCEEDED,
    TASK_FAILED,
    LOG,
    EXECUTION_FINISHED,
    VERIFICATION_ALERT,
    POLICY_MISMATCH;

    // 경보는 실행 상태와 관계없이 받고 상태를 바꾸지 않음
    public boolean isAlert() {
        return this == VERIFICATION_ALERT || this == POLICY_MISMATCH;
    }

    public boolean isTaskEvent() {
        return this == TASK_STARTED || this == TASK_SUCCEEDED || this == TASK_FAILED;
    }
}
