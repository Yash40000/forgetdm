package io.forgetdm.sync;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SyncSetMemberRepository extends JpaRepository<SyncSetMemberEntity, Long> {
    List<SyncSetMemberEntity> findBySyncSetId(Long syncSetId);
    void deleteBySyncSetId(Long syncSetId);
}
