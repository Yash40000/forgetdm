package io.forgetdm.cdc;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CdcCaptureRepository extends JpaRepository<CdcCaptureEntity, Long> {
    Optional<CdcCaptureEntity> findByDataSourceId(Long dataSourceId);
    Optional<CdcCaptureEntity> findBySlotName(String slotName);
    boolean existsByDataSourceId(Long dataSourceId);
    java.util.List<CdcCaptureEntity> findByStatus(String status);
}
