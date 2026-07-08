package io.forgetdm.dataset;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface DataSetVersionRepository extends JpaRepository<DataSetVersionEntity, Long> {
    List<DataSetVersionEntity> findByDatasetIdOrderByVersionNoDesc(Long datasetId);
    Optional<DataSetVersionEntity> findTopByDatasetIdOrderByVersionNoDesc(Long datasetId);
}
