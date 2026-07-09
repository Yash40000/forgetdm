package io.forgetdm.businessentity;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;
import java.util.Optional;

public interface BeEntityInstanceRepository extends JpaRepository<BeEntityInstanceEntity, Long> {
    List<BeEntityInstanceEntity> findByEntityIdOrderByUpdatedAtDesc(Long entityId);
    Optional<BeEntityInstanceEntity> findByEntityIdAndCanonicalKey(Long entityId, String canonicalKey);

    /** Row-locked lookup so concurrent materialize/refresh calls on the same capsule serialize
     *  instead of racing the UNIQUE(entity_id, canonical_key) constraint or double-incrementing versions. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<BeEntityInstanceEntity> findWithLockByEntityIdAndCanonicalKey(Long entityId, String canonicalKey);

    long countByEntityId(Long entityId);
}
