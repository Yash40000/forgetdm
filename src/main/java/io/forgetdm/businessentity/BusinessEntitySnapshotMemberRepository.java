package io.forgetdm.businessentity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BusinessEntitySnapshotMemberRepository extends JpaRepository<BusinessEntitySnapshotMemberEntity, Long> {
    List<BusinessEntitySnapshotMemberEntity> findBySnapshotIdOrderByIdAsc(Long snapshotId);
}
