package io.forgetdm.sync;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SyncRunRepository extends JpaRepository<SyncRunEntity, Long> {
    List<SyncRunEntity> findBySyncSetIdOrderByStartedAtDesc(Long syncSetId);
}
