package io.forgetdm.unstructured;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;

public interface UnstructuredJobRepository extends JpaRepository<UnstructuredJobEntity, Long> {
    List<UnstructuredJobEntity> findTop100ByOrderByCreatedAtDesc();
    List<UnstructuredJobEntity> findByFinishedAtBefore(Instant cutoff);
}
