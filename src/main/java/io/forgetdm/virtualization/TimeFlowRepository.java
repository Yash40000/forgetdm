package io.forgetdm.virtualization;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TimeFlowRepository extends JpaRepository<TimeFlowEntity, Long> {
    Optional<TimeFlowEntity> findFirstBySourceIdAndContainerTypeAndSchemaName(Long sourceId, String containerType, String schemaName);
    Optional<TimeFlowEntity> findFirstByVdbIdAndContainerType(Long vdbId, String containerType);
    List<TimeFlowEntity> findAllByOrderByIdDesc();
}
