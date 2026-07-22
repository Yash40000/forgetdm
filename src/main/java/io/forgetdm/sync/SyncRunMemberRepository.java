package io.forgetdm.sync;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SyncRunMemberRepository extends JpaRepository<SyncRunMemberEntity, Long> {
    List<SyncRunMemberEntity> findBySyncRunId(Long syncRunId);
}
