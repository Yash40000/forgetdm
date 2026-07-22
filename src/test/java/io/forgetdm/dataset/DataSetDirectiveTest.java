package io.forgetdm.dataset;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DataSetDirectiveTest {

    @Test
    void independentTableDisablesItsQ1AndQ2Traversal() {
        // trailing null is the OwnershipGuard (V61 tenancy) — unused by toDirectives
        DataSetService service = new DataSetService(
                null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
        TableProfileEntity profile = new TableProfileEntity();
        profile.setTableName("standalone_seed");
        profile.setIncluded(true);
        profile.setReferentialStrategy("INDEPENDENT");
        profile.setQ1Mode("YES");
        profile.setQ2Mode("DEFER");

        var directive = service.toDirectives(List.of(profile)).get(0);

        assertEquals("NO", directive.q1Mode());
        assertEquals("NO", directive.q2Mode());
    }
}
