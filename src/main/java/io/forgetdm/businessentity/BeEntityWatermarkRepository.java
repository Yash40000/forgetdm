package io.forgetdm.businessentity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BeEntityWatermarkRepository extends JpaRepository<BeEntityWatermarkEntity, Long> {
    List<BeEntityWatermarkEntity> findByInstanceIdOrderByMemberIdAsc(Long instanceId);
    Optional<BeEntityWatermarkEntity> findByInstanceIdAndMemberId(Long instanceId, Long memberId);
}
