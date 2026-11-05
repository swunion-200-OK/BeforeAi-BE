package site.siyoung.beforeaibe.repository;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.Role;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmployeeNumber(String employeeNumber);

    boolean existsByEmployeeNumber(String employeeNumber);

    // 계정 목록 첫 페이지 (GET /admin/users?departmentId=&role=). 필터는 null이면 전체.
    // createDate 기준 파라미터를 null로 바인딩하면 Postgres가 타입을 추론하지 못해 커서 유무로 쿼리를 분기한다.
    @Query("""
            select u from User u
            where (:departmentId is null or u.departmentId = :departmentId)
              and (:role is null or u.role = :role)
            order by u.createdAt desc, u.id desc
            """)
    List<User> findFirstPage(@Param("departmentId") UUID departmentId,
                             @Param("role") Role role,
                             Limit limit);

    // 커서(마지막으로 받은 항목의 createdAt, id) 다음 페이지
    @Query("""
            select u from User u
            where (:departmentId is null or u.departmentId = :departmentId)
              and (:role is null or u.role = :role)
              and (u.createdAt < :createdAt or (u.createdAt = :createdAt and u.id < :id))
            order by u.createdAt desc, u.id desc
            """)
    List<User> findPageAfter(@Param("departmentId") UUID departmentId,
                             @Param("role") Role role,
                             @Param("createdAt") Instant createdAt,
                             @Param("id") UUID id,
                             Limit limit);
}
