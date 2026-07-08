package io.forgetdm.businessentity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BusinessEntityMemberRepository extends JpaRepository<BusinessEntityMemberEntity, Long> {
    List<BusinessEntityMemberEntity> findByEntityIdOrderByOrdinalNoAscIdAsc(Long entityId);
    long countByEntityId(Long entityId);
    void deleteByEntityId(Long entityId);
}
