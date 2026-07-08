package io.forgetdm.virtualization;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VirtualSnapshotRepository extends JpaRepository<VirtualSnapshotEntity, Long> {
    List<VirtualSnapshotEntity> findByVdbIdOrderByCreatedAtDesc(Long vdbId);
    List<VirtualSnapshotEntity> findBySourceIdAndSnapshotTypeOrderByCreatedAtDesc(Long sourceId, String snapshotType);
}
