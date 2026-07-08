package io.forgetdm.reservation;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ReservationRepository extends JpaRepository<ReservationEntity, Long> {
    List<ReservationEntity> findByDataSourceIdAndTableNameAndStatus(Long ds, String table, String status);
    List<ReservationEntity> findByStatus(String status);
}
