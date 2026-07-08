package io.forgetdm.mainframe;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

public interface CopybookMaskRepository extends JpaRepository<CopybookMaskEntity, Long> {
    List<CopybookMaskEntity> findByCopybookId(Long copybookId);
    @Transactional
    void deleteByCopybookId(Long copybookId);
}
