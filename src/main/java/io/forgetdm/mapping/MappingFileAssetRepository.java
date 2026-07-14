package io.forgetdm.mapping;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MappingFileAssetRepository extends JpaRepository<MappingFileAssetEntity, Long> {
    List<MappingFileAssetEntity> findAllByOrderByCreatedAtDesc();
}
