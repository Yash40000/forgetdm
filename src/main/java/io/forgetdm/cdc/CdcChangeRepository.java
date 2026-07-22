package io.forgetdm.cdc;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CdcChangeRepository extends JpaRepository<CdcChangeEntity, Long> {

    List<CdcChangeEntity> findByDataSourceIdOrderByIdDesc(Long dataSourceId, Pageable page);

    List<CdcChangeEntity> findByCaptureIdAndIdGreaterThanOrderByIdAsc(Long captureId, Long afterId, Pageable page);

    long countByDataSourceId(Long dataSourceId);

    @Query("select coalesce(max(c.id),0) from CdcChangeEntity c where c.captureId = :captureId")
    long maxIdForCapture(@Param("captureId") Long captureId);

    long deleteByCaptureIdAndIdLessThanEqual(Long captureId, Long id);
}
