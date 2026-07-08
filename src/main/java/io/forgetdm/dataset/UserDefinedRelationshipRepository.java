package io.forgetdm.dataset;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface UserDefinedRelationshipRepository extends JpaRepository<UserDefinedRelationshipEntity, Long> {
    List<UserDefinedRelationshipEntity> findByDatasetId(Long datasetId);
    void deleteByDatasetId(Long datasetId);
}
