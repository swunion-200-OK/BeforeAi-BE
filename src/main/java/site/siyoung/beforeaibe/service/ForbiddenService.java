package site.siyoung.beforeaibe.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.siyoung.beforeaibe.dto.common.CursorPageResponse;
import site.siyoung.beforeaibe.dto.forbidden.ForbiddenProposalCreateRequest;
import site.siyoung.beforeaibe.dto.forbidden.ForbiddenProposalConfirmRequest;
import site.siyoung.beforeaibe.dto.forbidden.ForbiddenProposalConfirmResponse;
import site.siyoung.beforeaibe.dto.forbidden.ForbiddenProposalRejectRequest;
import site.siyoung.beforeaibe.dto.forbidden.ForbiddenProposalResponse;
import site.siyoung.beforeaibe.dto.forbidden.ForbiddenProposalStatusResponse;
import site.siyoung.beforeaibe.dto.forbidden.ForbiddenRuleCreateRequest;
import site.siyoung.beforeaibe.dto.forbidden.ForbiddenRuleReleaseRequest;
import site.siyoung.beforeaibe.dto.forbidden.ForbiddenRuleResponse;
import site.siyoung.beforeaibe.dto.forbidden.ForbiddenRuleStatusResponse;
import site.siyoung.beforeaibe.entity.ForbiddenProposal;
import site.siyoung.beforeaibe.entity.ForbiddenRule;
import site.siyoung.beforeaibe.entity.enums.ForbiddenProposalStatus;
import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.exception.ErrorCode;
import site.siyoung.beforeaibe.repository.DepartmentRepository;
import site.siyoung.beforeaibe.repository.ForbiddenProposalRepository;
import site.siyoung.beforeaibe.repository.ForbiddenRuleRepository;
import site.siyoung.beforeaibe.support.CreatedAtCursor;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ForbiddenService {
    private final ForbiddenCommandService commands;
    private final PolicyDeploymentService deployments;
    private final ForbiddenProposalRepository proposals;
    private final ForbiddenRuleRepository rules;
    private final DepartmentRepository departments;

    public ForbiddenProposalResponse propose(UUID userId, UUID departmentId, ForbiddenProposalCreateRequest body) {
        return commands.propose(userId, departmentId, body);
    }
    public ForbiddenProposalConfirmResponse confirm(UUID adminId, UUID id, ForbiddenProposalConfirmRequest body) {
        return deploy(commands.confirm(adminId, id, body));
    }
    public ForbiddenProposalStatusResponse reject(UUID id, ForbiddenProposalRejectRequest body) {
        return commands.reject(id, body);
    }
    public ForbiddenRuleResponse add(UUID adminId, ForbiddenRuleCreateRequest body) {
        return deploy(commands.add(adminId, body));
    }
    public ForbiddenRuleStatusResponse release(UUID adminId, UUID id, ForbiddenRuleReleaseRequest body) {
        return deploy(commands.release(adminId, id, body));
    }
    private <T> T deploy(ForbiddenCommandService.Change<T> change) {
        if (change.policyId() != null) deployments.deploy(change.policyId());
        return change.response();
    }

    @Transactional(readOnly = true)
    public CursorPageResponse<ForbiddenProposalResponse> proposals(ForbiddenProposalStatus status, String cursor, int limit) {
        List<ForbiddenProposal> rows;
        if (cursor == null || cursor.isBlank()) rows = proposals.firstPage(status, Limit.of(limit + 1));
        else {
            var c = CreatedAtCursor.decode(cursor);
            rows = proposals.pageAfter(status, c.createdAt(), c.id(), Limit.of(limit + 1));
        }
        boolean more = rows.size() > limit;
        var page = more ? rows.subList(0, limit) : rows;
        String next = more ? new CreatedAtCursor(page.getLast().getCreatedAt(), page.getLast().getId()).encode() : null;
        return new CursorPageResponse<>(page.stream().map(ForbiddenProposalResponse::from).toList(), next);
    }

    @Transactional(readOnly = true)
    public CursorPageResponse<ForbiddenRuleResponse> rules(UUID departmentId, String cursor, int limit) {
        if (!departments.existsById(departmentId)) throw new ApiException(ErrorCode.NOT_FOUND);
        List<ForbiddenRule> rows;
        if (cursor == null || cursor.isBlank()) rows = rules.firstPage(departmentId, Limit.of(limit + 1));
        else {
            var c = CreatedAtCursor.decode(cursor);
            rows = rules.pageAfter(departmentId, c.createdAt(), c.id(), Limit.of(limit + 1));
        }
        boolean more = rows.size() > limit;
        var page = more ? rows.subList(0, limit) : rows;
        String next = more ? new CreatedAtCursor(page.getLast().getCreatedAt(), page.getLast().getId()).encode() : null;
        return new CursorPageResponse<>(page.stream().map(ForbiddenRuleResponse::from).toList(), next);
    }
}
