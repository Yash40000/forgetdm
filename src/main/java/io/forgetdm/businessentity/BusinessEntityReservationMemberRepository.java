package io.forgetdm.businessentity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BusinessEntityReservationMemberRepository extends JpaRepository<BusinessEntityReservationMemberEntity, Long> {
    List<BusinessEntityReservationMemberEntity> findByReservationIdOrderByIdAsc(Long reservationId);
}
