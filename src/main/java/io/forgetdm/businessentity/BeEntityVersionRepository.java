package io.forgetdm.businessentity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BeEntityVersionRepository extends JpaRepository<BeEntityVersionEntity, Long> {
    List<BeEntityVersionEntity> findByInstanceIdOrderByVersionNoDesc(Long instanceId);
    Optional<BeEntityVersionEntity> findByInstanceIdAndVersionNo(Long instanceId, int versionNo);
}
