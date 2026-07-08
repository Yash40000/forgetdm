package io.forgetdm.mainframe;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MainframeJobFileRepository extends JpaRepository<MainframeJobFileEntity, Long> {
    List<MainframeJobFileEntity> findByJobIdOrderByOrdinalAsc(Long jobId);
}
