package site.siyoung.beforeaibe.dto.approvaldocument;

import site.siyoung.beforeaibe.entity.ApprovalDocument;
import site.siyoung.beforeaibe.entity.ApprovalDocumentVersion;
import site.siyoung.beforeaibe.entity.Plan;
import site.siyoung.beforeaibe.entity.Project;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.User;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// 결재 문서 한 판의 내용. 미리보기와 PDF 다운로드가 같은 모델로 그려 둘의 내용이 어긋나지 않게 함
public record ApprovalDocumentResponse(
        UUID documentId,
        UUID requestId,
        Instant documentCreatedAt,
        int version,
        int currentVersion,
        Status status,
        String projectName,
        String requesterName,
        String requesterDepartmentName,
        Instant draftedAt,
        String prompt,
        String repository,
        String baseCommit,
        String planHash,
        List<ApprovalSlotResponse> slots,
        // 실행이 시작되기 전에는 비어 있음
        List<TaskResultResponse> results
) {

    public static ApprovalDocumentResponse of(ApprovalDocument document, ApprovalDocumentVersion version,
                                              Status status, Request request, Project project, User requester,
                                              String requesterDepartmentName, Plan plan,
                                              List<ApprovalSlotResponse> slots, List<TaskResultResponse> results) {
        return new ApprovalDocumentResponse(document.getId(), request.getId(), document.getCreatedAt(),
                version.getVersion(), document.getCurrentVersion(), status, project.getName(),
                requester == null ? null : requester.getName(), requesterDepartmentName, version.getCreatedAt(),
                request.getPrompt(), plan.getRepository(), plan.getBaseCommit(), plan.getPlanHash(), slots, results);
    }

    // PDF는 최신 판이 APPROVED일 때만 받을 수 있음
    public boolean downloadable() {
        return status == Status.APPROVED;
    }

    public enum Status {
        // 최신 판, 필요한 승인이 아직 남음
        IN_PROGRESS,
        // 최신 판, 금지 작업을 뺀 모든 작업의 필요 승인이 끝남
        APPROVED,
        // 최신 판, 요청자가 Plan을 고치는 중(재분석·재검토·재승인 필요). 다시 제출되면 다음 판이 생김
        REVISING,
        // 바뀐 Plan이 다시 제출되어 기록으로 남은 이전 판
        SUPERSEDED,
        REJECTED,
        CANCELED,
        KILLED
    }
}
