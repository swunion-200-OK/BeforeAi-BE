package site.siyoung.beforeaibe.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import site.siyoung.beforeaibe.entity.Plan;

import java.util.UUID;

public interface PlanRepository extends JpaRepository<Plan, UUID> {
}
