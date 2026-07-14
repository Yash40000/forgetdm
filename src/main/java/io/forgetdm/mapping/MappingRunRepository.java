package io.forgetdm.mapping;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MappingRunRepository extends JpaRepository<MappingRunEntity, Long> {
    List<MappingRunEntity> findTop100ByOrderByCreatedAtDesc();
}
