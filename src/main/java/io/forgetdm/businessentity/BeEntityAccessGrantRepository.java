package io.forgetdm.businessentity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BeEntityAccessGrantRepository extends JpaRepository<BeEntityAccessGrantEntity, Long> {
    List<BeEntityAccessGrantEntity> findByEntityIdOrderByGrantedAtDesc(Long entityId);
    List<BeEntityAccessGrantEntity> findByInstanceIdOrderByGrantedAtDesc(Long instanceId);
    List<BeEntityAccessGrantEntity> findByInstanceIdAndRevokedFalse(Long instanceId);
}
