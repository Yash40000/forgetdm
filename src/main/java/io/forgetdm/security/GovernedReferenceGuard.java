package io.forgetdm.security;

import io.forgetdm.common.ApiException;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceRepository;
import io.forgetdm.policy.MaskingPolicyEntity;
import io.forgetdm.policy.MaskingPolicyRepository;
import org.springframework.stereotype.Component;

/**
 * Authorizes governed objects used indirectly by another saved object or queued job.
 *
 * <p>Route authorization and ownership checks on the parent are insufficient when its payload can
 * contain identifiers for another tenant's data source or policy. Resolve those identifiers while
 * the request principal is still present, before the payload is saved or handed to a background
 * worker.</p>
 */
@Component
public class GovernedReferenceGuard {

    private final DataSourceRepository dataSources;
    private final MaskingPolicyRepository policies;
    private final OwnershipGuard ownership;

    public GovernedReferenceGuard(DataSourceRepository dataSources,
                                  MaskingPolicyRepository policies,
                                  OwnershipGuard ownership) {
        this.dataSources = dataSources;
        this.policies = policies;
        this.ownership = ownership;
    }

    public DataSourceEntity dataSource(Long id) {
        if (id == null) return null;
        DataSourceEntity source = dataSources.findById(id)
                .orElseThrow(() -> ApiException.notFound("Data source " + id + " not found"));
        ownership.assertCanSee("data source", id, source.getOwnerUserId(),
                source.getOwnerGroupId(), source.getVisibility());
        return source;
    }

    /** Non-throwing form for tenant-scoped list queries. */
    public boolean canSeeDataSource(Long id) {
        if (id == null) return true;
        return dataSources.findById(id)
                .map(source -> ownership.canSee(source.getOwnerUserId(), source.getOwnerGroupId(),
                        source.getVisibility()))
                .orElse(false);
    }

    public MaskingPolicyEntity policy(Long id) {
        if (id == null) return null;
        MaskingPolicyEntity policy = policies.findById(id)
                .orElseThrow(() -> ApiException.notFound("Policy " + id + " not found"));
        ownership.assertCanSee("policy", id, policy.getOwnerUserId(),
                policy.getOwnerGroupId(), policy.getVisibility());
        if (policy.getDataSourceId() != null) dataSource(policy.getDataSourceId());
        return policy;
    }
}
