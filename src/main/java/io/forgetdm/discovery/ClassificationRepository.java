package io.forgetdm.discovery;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ClassificationRepository extends JpaRepository<ClassificationEntity, Long> {
    List<ClassificationEntity> findByDataSourceId(Long dataSourceId);
    List<ClassificationEntity> findByDataSourceIdAndSchemaName(Long dataSourceId, String schemaName);
    List<ClassificationEntity> findByDataSourceIdAndStatus(Long dataSourceId, String status);
    List<ClassificationEntity> findByDataSourceIdAndSchemaNameAndStatus(Long dataSourceId, String schemaName, String status);
    List<ClassificationEntity> findByDataSourceIdAndSchemaNameAndTableName(Long dataSourceId, String schemaName, String tableName);
    Optional<ClassificationEntity> findByDataSourceIdAndTableNameAndColumnName(Long ds, String t, String c);
    Optional<ClassificationEntity> findByDataSourceIdAndSchemaNameAndTableNameAndColumnName(Long ds, String schema, String t, String c);
    void deleteByDataSourceId(Long dataSourceId);
    void deleteByDataSourceIdAndSchemaName(Long dataSourceId, String schemaName);
}
