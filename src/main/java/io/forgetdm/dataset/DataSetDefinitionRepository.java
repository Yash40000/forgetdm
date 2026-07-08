package io.forgetdm.dataset;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface DataSetDefinitionRepository extends JpaRepository<DataSetDefinitionEntity, Long> {
    Optional<DataSetDefinitionEntity> findByName(String name);
    List<DataSetDefinitionEntity> findByDataSourceId(Long dataSourceId);
}
