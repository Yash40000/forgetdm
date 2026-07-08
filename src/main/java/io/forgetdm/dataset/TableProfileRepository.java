package io.forgetdm.dataset;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface TableProfileRepository extends JpaRepository<TableProfileEntity, Long> {
    List<TableProfileEntity> findByDatasetId(Long datasetId);
    Optional<TableProfileEntity> findByDatasetIdAndTableName(Long datasetId, String tableName);
    void deleteByDatasetId(Long datasetId);
}
