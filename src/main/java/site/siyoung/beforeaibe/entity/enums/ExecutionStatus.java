package site.siyoung.beforeaibe.entity.enums;

// 웹쉘 실행 세션 상태. 롤백 Plan 실행 결과도 같은 값으로 받고, 요청 상태(ROLLED_BACK 등)는 Plan 종류로 구분함
public enum ExecutionStatus {
    RUNNING,
    COMPLETED,
    PARTIALLY_COMPLETED,
    FAILED,
    KILLED
}
