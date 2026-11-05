package site.siyoung.beforeaibe.dto.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import site.siyoung.beforeaibe.entity.enums.Network;
import site.siyoung.beforeaibe.entity.enums.Risk;
import site.siyoung.beforeaibe.entity.enums.TaskType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// 진행 상황 트리 노드 (API 명세서 2.6). 노드 종류(REQUEST·PLAN·TASK·APPROVAL·STEP)마다 쓰는 필드만 채움
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProgressNodeResponse(
        String id,
        String type,
        String label,
        String status,
        String planHash,
        TaskType taskType,
        Risk risk,
        Network network,
        Instant at,
        List<UUID> candidates,
        Boolean collapsed,
        List<String> dependsOn,
        LogRange logRange,
        List<ProgressNodeResponse> children
) {

    public static ProgressNodeResponse request(String id, String label, String status,
                                               List<ProgressNodeResponse> children) {
        return new ProgressNodeResponse(id, "REQUEST", label, status, null, null, null, null, null, null, null,
                null, null, children);
    }

    public static ProgressNodeResponse currentPlan(String id, String label, String planHash,
                                                   List<ProgressNodeResponse> children) {
        return new ProgressNodeResponse(id, "PLAN", label, "CURRENT", planHash, null, null, null, null, null, null,
                null, null, children);
    }

    // 지난 Plan은 접힌 상태로 자식 없이 내려 줌
    public static ProgressNodeResponse supersededPlan(String id, String label) {
        return new ProgressNodeResponse(id, "PLAN", label, "SUPERSEDED", null, null, null, null, null, null, true,
                null, null, List.of());
    }

    public static ProgressNodeResponse task(String id, String label, TaskType taskType, Risk risk, String status,
                                            List<String> dependsOn, List<ProgressNodeResponse> children) {
        return new ProgressNodeResponse(id, "TASK", label, status, null, taskType, risk, null, null, null, null,
                dependsOn, null, children);
    }

    public static ProgressNodeResponse approved(String id, String label, Network network, Instant at) {
        return new ProgressNodeResponse(id, "APPROVAL", label, "APPROVED", null, null, null, network, at, null, null,
                null, null, null);
    }

    // 승인 대기 노드는 label 대신 candidates를 가짐
    public static ProgressNodeResponse pendingApproval(String id, List<UUID> candidates) {
        return new ProgressNodeResponse(id, "APPROVAL", null, "PENDING", null, null, null, null, null, candidates,
                null, null, null, null);
    }

    // 실행 단계. 웹쉘 서명이 확인된 실행 이벤트로만 만들고 logRange로 해당 작업의 실행 로그 구간을 가리킴
    public static ProgressNodeResponse step(String id, String label, String status, LogRange logRange) {
        return new ProgressNodeResponse(id, "STEP", label, status, null, null, null, null, null, null, null, null,
                logRange, null);
    }

    // 실행 로그 sequence 구간. 실행 중이면 to는 null
    public record LogRange(Long from, Long to) {
    }
}
