package site.siyoung.beforeaibe.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import site.siyoung.beforeaibe.entity.ProjectOwner;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ProjectOwnerRepository extends JpaRepository<ProjectOwner, UUID> {

    // 목록 응답의 담당자를 프로젝트마다 따로 조회하지 않도록 사용자까지 한 번에 가져옴
    @Query("select o from ProjectOwner o join fetch o.user where o.project.id in :projectIds")
    List<ProjectOwner> findWithUserByProjectIdIn(@Param("projectIds") Collection<UUID> projectIds);
}
