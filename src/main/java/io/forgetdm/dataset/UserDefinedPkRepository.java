package io.forgetdm.dataset;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface UserDefinedPkRepository extends JpaRepository<UserDefinedPkEntity, Long> {
    List<UserDefinedPkEntity> findByDatasetId(Long datasetId);
    Optional<UserDefinedPkEntity> findByDatasetIdAndTableName(Long datasetId, String tableName);
    void deleteByDatasetId(Long datasetId);
}
