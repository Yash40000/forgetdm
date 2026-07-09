package io.forgetdm.businessentity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BeEntityFragmentRepository extends JpaRepository<BeEntityFragmentEntity, Long> {
    List<BeEntityFragmentEntity> findByInstanceIdOrderByIdAsc(Long instanceId);
    List<BeEntityFragmentEntity> findByInstanceIdAndStatusOrderByIdAsc(Long instanceId, String status);
    Optional<BeEntityFragmentEntity> findByInstanceIdAndMemberIdAndStatus(Long instanceId, Long memberId, String status);
    List<BeEntityFragmentEntity> findByInstanceIdAndVersionNoOrderByIdAsc(Long instanceId, int versionNo);
}
