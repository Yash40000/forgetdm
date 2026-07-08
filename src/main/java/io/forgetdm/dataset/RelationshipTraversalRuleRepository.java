package io.forgetdm.dataset;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface RelationshipTraversalRuleRepository extends JpaRepository<RelationshipTraversalRuleEntity, Long> {
    List<RelationshipTraversalRuleEntity> findByDatasetId(Long datasetId);
    Optional<RelationshipTraversalRuleEntity> findByDatasetIdAndParentTableAndChildTableAndRelSource(
            Long datasetId, String parentTable, String childTable, String relSource);
    void deleteByDatasetId(Long datasetId);
}
