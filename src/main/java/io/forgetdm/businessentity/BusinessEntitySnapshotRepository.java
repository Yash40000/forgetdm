package io.forgetdm.businessentity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BusinessEntitySnapshotRepository extends JpaRepository<BusinessEntitySnapshotEntity, Long> {
    List<BusinessEntitySnapshotEntity> findByEntityIdOrderByCreatedAtDesc(Long entityId);
}
