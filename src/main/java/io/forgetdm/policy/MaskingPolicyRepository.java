package io.forgetdm.policy;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MaskingPolicyRepository extends JpaRepository<MaskingPolicyEntity, Long> {
    List<MaskingPolicyEntity> findByDataSourceIdAndSchemaName(Long dataSourceId, String schemaName);
}
