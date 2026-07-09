package io.forgetdm.businessentity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BeEntityLineageEventRepository extends JpaRepository<BeEntityLineageEventEntity, Long> {
    List<BeEntityLineageEventEntity> findByInstanceIdOrderByOccurredAtDesc(Long instanceId);
}
