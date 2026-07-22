package io.forgetdm.dataset;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.policy.MaskingPolicyRepository;
import io.forgetdm.policy.MaskingRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class DataScopeVersionIsolationTest {

    private final DataSetVersionRepository versions = mock(DataSetVersionRepository.class);
    private final DataSetService datasets = mock(DataSetService.class);
    private DataScopeVersionService service;

    @BeforeEach
    void setUp() {
        DataSetVersionEntity alphaVersion = new DataSetVersionEntity();
        alphaVersion.setDatasetId(91L);
        alphaVersion.setVersionNo(3);
        alphaVersion.setSnapshotJson("{}");
        when(versions.findById(701L)).thenReturn(Optional.of(alphaVersion));
        when(datasets.get(91L)).thenThrow(ApiException.forbidden("This DataScope blueprint belongs to another group"));
        service = new DataScopeVersionService(versions, datasets,
                mock(MaskingPolicyRepository.class), mock(MaskingRuleRepository.class), mock(AuditService.class));
    }

    @Test
    void blocksCrossGroupVersionRead() {
        ApiException denial = assertThrows(ApiException.class, () -> service.getVersion(701L));
        assertEquals(HttpStatus.FORBIDDEN, denial.getStatus());
    }

    @Test
    void blocksCrossGroupVersionCompare() {
        ApiException denial = assertThrows(ApiException.class, () -> service.compare(701L, null));
        assertEquals(HttpStatus.FORBIDDEN, denial.getStatus());
    }

    @Test
    void blocksCrossGroupVersionRestoreBeforeAnySnapshotWrite() {
        ApiException denial = assertThrows(ApiException.class, () -> service.restore(701L));
        assertEquals(HttpStatus.FORBIDDEN, denial.getStatus());
        verify(versions, never()).save(any());
    }
}
