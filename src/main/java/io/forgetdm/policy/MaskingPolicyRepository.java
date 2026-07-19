package io.forgetdm.policy;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface MaskingPolicyRepository extends JpaRepository<MaskingPolicyEntity, Long> {
    List<MaskingPolicyEntity> findByDataSourceIdAndSchemaName(Long dataSourceId, String schemaName);
    Optional<MaskingPolicyEntity> findByNameIgnoreCase(String name);
}
