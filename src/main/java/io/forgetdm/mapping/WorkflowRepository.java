package io.forgetdm.mapping;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WorkflowRepository extends JpaRepository<WorkflowEntity, Long> {
    Optional<WorkflowEntity> findByNameIgnoreCase(String name);
}
