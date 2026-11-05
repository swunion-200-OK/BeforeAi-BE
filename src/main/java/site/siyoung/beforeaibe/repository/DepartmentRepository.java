package site.siyoung.beforeaibe.repository;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import site.siyoung.beforeaibe.entity.Department;

import java.util.List;
import java.util.UUID;

public interface DepartmentRepository extends JpaRepository<Department, UUID> {

    boolean existsByName(String name);

    List<Department> findByOrderByNameAscIdAsc(Limit limit);

    // 커서(마지막으로 받은 항목의 name, id) 다음 페이지
    @Query("""
            select d from Department d
            where (d.name > :name or (d.name = :name and d.id > :id))
            order by d.name asc, d.id asc
            """)
    List<Department> findPageAfter(@Param("name") String name, @Param("id") UUID id, Limit limit);
}
