package io.forgetdm.dataset;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ColumnOverrideRepository extends JpaRepository<ColumnOverrideEntity, Long> {
    List<ColumnOverrideEntity> findByDatasetId(Long datasetId);
    List<ColumnOverrideEntity> findByDatasetIdAndTableName(Long datasetId, String tableName);
    Optional<ColumnOverrideEntity> findByDatasetIdAndTableNameAndColumnName(Long datasetId, String tableName, String columnName);
    void deleteByDatasetId(Long datasetId);
    void deleteByDatasetIdAndTableName(Long datasetId, String tableName);
}
