package site.siyoung.beforeaibe.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import site.siyoung.beforeaibe.entity.Approval;

import java.util.List;
import java.util.UUID;

public interface ApprovalRepository extends JpaRepository<Approval, UUID> {

    // 현재 Plan에 제출된 승인 (요청 상세의 승인 현황, 진행 트리의 APPROVAL 노드)
    List<Approval> findByPlan_IdOrderByCreatedAtAscIdAsc(UUID planId);
}
