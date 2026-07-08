package io.forgetdm.businessentity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BusinessEntityReservationRepository extends JpaRepository<BusinessEntityReservationEntity, Long> {
    List<BusinessEntityReservationEntity> findByEntityIdOrderByCreatedAtDesc(Long entityId);
    List<BusinessEntityReservationEntity> findByEntityIdAndStatus(Long entityId, String status);
    List<BusinessEntityReservationEntity> findByStatus(String status);
}
