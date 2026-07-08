package io.forgetdm.policy;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MaskingRuleRepository extends JpaRepository<MaskingRuleEntity, Long> {
    List<MaskingRuleEntity> findByPolicyId(Long policyId);
    void deleteByPolicyId(Long policyId);
}
