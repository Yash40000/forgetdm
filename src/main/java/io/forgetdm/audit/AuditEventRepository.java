package io.forgetdm.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AuditEventRepository extends JpaRepository<AuditEventEntity, Long> {

    List<AuditEventEntity> findAllByOrderByIdDesc(Pageable pageable);

    List<AuditEventEntity> findAllByOrderBySeqAsc();

    Optional<AuditEventEntity> findFirstByOrderBySeqDesc();

    @Query("""
            SELECT e FROM AuditEventEntity e
            WHERE (:actor = '' OR LOWER(e.actor) = LOWER(:actor))
              AND (:action = '' OR e.action = :action)
              AND (:category = '' OR e.category = :category)
              AND (:outcome = '' OR e.outcome = :outcome)
              AND (:resourceType = '' OR e.resourceType = :resourceType)
              AND e.createdAt >= :from
              AND e.createdAt <= :to
              AND (:q = ''
                   OR LOWER(e.detail) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(e.action) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(e.resourceName) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(e.actor) LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY e.id DESC
            """)
    Page<AuditEventEntity> search(@Param("actor") String actor,
                                  @Param("action") String action,
                                  @Param("category") String category,
                                  @Param("outcome") String outcome,
                                  @Param("resourceType") String resourceType,
                                  @Param("from") Instant from,
                                  @Param("to") Instant to,
                                  @Param("q") String q,
                                  Pageable pageable);

    @Query("SELECT DISTINCT e.action FROM AuditEventEntity e WHERE e.action IS NOT NULL ORDER BY e.action")
    List<String> distinctActions();

    @Query("SELECT DISTINCT e.category FROM AuditEventEntity e WHERE e.category IS NOT NULL ORDER BY e.category")
    List<String> distinctCategories();

    @Query("SELECT DISTINCT e.actor FROM AuditEventEntity e WHERE e.actor IS NOT NULL ORDER BY e.actor")
    List<String> distinctActors();

    long countByOutcome(String outcome);
}
